package com.voxticket.service;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.repository.RefundRepository;
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
 */
@Service
@Transactional
public class RefundService {

    private final RefundRepository refundRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;

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

    public Refund markSucceeded(UUID refundId) {
        Refund refund = get(refundId);
        requirePending(refund);
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setCompletedAt(Instant.now());
        applyToPaymentStatus(refund.getPayment());
        return refund;
    }

    public Refund markFailed(UUID refundId) {
        Refund refund = get(refundId);
        requirePending(refund);
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailedAt(Instant.now());
        // Payment status is deliberately left untouched - the attempt failed, the money's
        // disposition hasn't changed, and a retry/manual-resolution path is future work.
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

    private void requirePending(Refund refund) {
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new IllegalStateException("Refund " + refund.getRefundNumber() + " is " + refund.getStatus() + ", expected PENDING");
        }
    }
}