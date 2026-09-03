package com.voxticket.service;

import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationNotEligibleException;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §35. Executes a cancellation once one is decided upon - it does NOT
 * decide whether to proceed on its own. It re-checks eligibility itself
 * (never trusts a previously-read result), so this method is safe to call
 * defensively, but it is NOT the confirmation/OTP boundary.
 *
 * <p><b>Security note:</b> this performs a real, irreversible mutation the
 * moment it's called. It must only ever be invoked by Phase 8's guarded
 * procedure after Phase 8's explicit confirmation and (per the resolved
 * identity policy) Phase 9's OTP verification have already happened. This
 * class has no awareness of confirmation or OTP - that is intentional; it
 * is not this class's job, and it must not silently grow that
 * responsibility later.
 */
@Service
@Transactional
public class CancellationService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CancellationPolicyService cancellationPolicyService;
    private final RefundService refundService;
    private final SupportTicketRepository supportTicketRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;

    public CancellationService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            CancellationPolicyService cancellationPolicyService,
            RefundService refundService,
            SupportTicketRepository supportTicketRepository,
            ReferenceNumberGenerator referenceNumberGenerator) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.cancellationPolicyService = cancellationPolicyService;
        this.refundService = refundService;
        this.supportTicketRepository = supportTicketRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
    }

    public CancellationResult cancel(VerifiedOrderRef orderRef) {
        Order order = orderRepository.findById(orderRef.orderId()).orElseThrow();
        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getId())
                .orElseThrow(() -> new IllegalStateException("Order has no payment record: " + order.getOrderNumber()));

        CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, payment);
        if (!eligibility.eligible()) {
            throw new CancellationNotEligibleException(order.getOrderNumber(), eligibility.denialReason());
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());

        Refund refund = null;
        SupportTicket manualReviewTicket = null;

        switch (eligibility.paymentConsequence()) {
            case NO_REFUND_REQUIRED -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.VOIDED);
                }
                // FAILED payments stay FAILED - that's already the accurate terminal state.
            }
            case VOID_AUTHORIZATION -> payment.setStatus(PaymentStatus.VOIDED);
            case REFUND_REQUIRED -> refund = refundService.initiateRefund(order, payment, null, payment.getAmount(), RefundReason.CANCELLATION);
            case MANUAL_REVIEW_REQUIRED -> manualReviewTicket = supportTicketRepository.save(new SupportTicket(
                    referenceNumberGenerator.ticketNumber(),
                    order.getCustomer(),
                    order,
                    TicketCategory.PAYMENT,
                    TicketPriority.HIGH,
                    "Order " + order.getOrderNumber() + " was cancelled while its payment was PENDING (non-COD)."
                            + " Payment resolution needs manual review."));
        }

        return new CancellationResult(order.getOrderNumber(), eligibility.paymentConsequence(), refund, manualReviewTicket);
    }

    public record CancellationResult(String orderNumber, PaymentConsequence paymentConsequence, Refund refund, SupportTicket manualReviewTicket) {
    }
}