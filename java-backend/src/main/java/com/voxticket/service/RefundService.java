package com.voxticket.service;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §32. Creation and state-transition rules for refunds only - no
 * provider integration. {@link #markSucceeded}/{@link #markFailed} simulate
 * what a real payment provider's async webhook would report; an actual
 * scheduled/simulated provider-outcome job is deferred (not required for
 * this phase's acceptance goal, which is about correct state transitions,
 * not about automatically generating them).
 *
 * <p>Payment.status only changes once a refund actually SUCCEEDS - not when
 * it's merely initiated (PENDING). This keeps "money has settled" (Payment)
 * cleanly separate from "a refund is in progress" (Refund), rather than
 * introducing an ambiguous intermediate Payment status.
 *
 * <p>Terminal transitions are exactly-once: each is a single atomic
 * conditional UPDATE ({@code ... where id = :id and status = PENDING}),
 * so of any racing transitions exactly one wins and every loser is
 * rejected. The check and the mutation are one database statement - there
 * is no in-memory check-then-act window for a racer to slip through.
 *
 * <p>Bulk UPDATEs bypass the persistence context, so after a winning
 * transition the managed entity is refreshed from the database before it
 * is used or returned. Without that, a caller sharing the transaction
 * (e.g. initiate-then-transition in one unit of work) would keep seeing
 * the stale PENDING state.
 */
@Service
@Transactional
public class RefundService {

    private final RefundRepository refundRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;

    @PersistenceContext
    private EntityManager entityManager;

    public RefundService(RefundRepository refundRepository, ReferenceNumberGenerator referenceNumberGenerator) {
        this.refundRepository = refundRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
    }

    public Refund initiateRefund(Order order, Payment payment, ReturnRequest returnRequest, BigDecimal amount, RefundReason reason) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive: " + amount);
        }
        Refund refund = new Refund(referenceNumberGenerator.refundNumber(), order, payment, returnRequest, amount, reason);
        return refundRepository.save(refund);
    }

    /**
     * Marks a PENDING refund as SUCCEEDED and recomputes the payment's
     * aggregate refund status.
     *
     * <p>The PENDING -&gt; SUCCEEDED step is one atomic conditional UPDATE:
     * the database evaluates {@code status = PENDING} while holding the row
     * write-lock taken by the UPDATE itself, so of a racing success/failure
     * pair exactly one wins. The loser (zero rows updated) is rejected with
     * {@link IllegalStateException} - the same contract the previous
     * pessimistic-lock version offered, but without depending on
     * {@code SELECT ... FOR UPDATE} blocking timing, which empirically let
     * both transitions of a race apply.
     *
     * @throws IllegalArgumentException if the refund id is unknown
     * @throws IllegalStateException if the refund is no longer PENDING
     */
    public Refund markSucceeded(UUID refundId) {
        int updated = refundRepository.markSucceededIfPending(refundId, Instant.now());
        if (updated == 0) {
            throw pendingViolation(refundId);
        }
        // Bulk UPDATE bypasses the persistence context: re-read the row and
        // refresh the managed instance so callers sharing this transaction
        // see SUCCEEDED (not the stale PENDING), then apply the payment
        // aggregation and return a fully populated entity.
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " vanished mid-transition"));
        entityManager.refresh(refund);
        applyToPaymentStatus(refund.getPayment());
        return refund;
    }

    /**
     * Marks a PENDING refund as FAILED, leaving the payment status
     * untouched - the attempt failed, the money's disposition hasn't
     * changed, and a retry/manual-resolution path is future work.
     * Same atomic exactly-one-winner guarantee as {@link #markSucceeded(UUID)}.
     *
     * @throws IllegalArgumentException if the refund id is unknown
     * @throws IllegalStateException if the refund is no longer PENDING
     */
    public Refund markFailed(UUID refundId) {
        int updated = refundRepository.markFailedIfPending(refundId, Instant.now());
        if (updated == 0) {
            throw pendingViolation(refundId);
        }
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " vanished mid-transition"));
        entityManager.refresh(refund);
        return refund;
    }

    private void applyToPaymentStatus(Payment payment) {
        BigDecimal totalSucceeded = refundRepository.findByPaymentId(payment.getId()).stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCEEDED)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        payment.setStatus(totalSucceeded.compareTo(payment.getAmount()) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
    }

    private Refund get(UUID refundId) {
        return refundRepository.findById(refundId).orElseThrow(() -> new IllegalArgumentException("Unknown refund: " + refundId));
    }

    /**
     * Builds the rejection for a terminal transition attempted on a refund
     * that is no longer PENDING, preserving the previous error contract:
     * unknown id -&gt; {@link IllegalArgumentException}, terminal state
     * -&gt; {@link IllegalStateException} naming the refund number and its
     * current status.
     */
    private RuntimeException pendingViolation(UUID refundId) {
        return refundRepository.findById(refundId)
                .map(refund -> (RuntimeException) new IllegalStateException(
                        "Refund " + refund.getRefundNumber() + " is " + refund.getStatus() + ", expected PENDING"))
                .orElseGet(() -> new IllegalArgumentException("Unknown refund: " + refundId));
    }
}
