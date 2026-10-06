package com.voxticket.policy;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import org.springframework.stereotype.Service;

/**
 * Spec §38. Pure, deterministic, side-effect-free eligibility check. Takes
 * entities directly and reads from them only - it never queries a
 * repository itself, which is what makes it trivial to unit test without a
 * database or Spring context.
 */
@Service
public class CancellationPolicyService {

    public CancellationEligibility evaluate(Order order, Payment payment) {
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return CancellationEligibility.denied(CancellationDenialReason.ALREADY_CANCELLED);
        }
        if (order.getOrderStatus() == OrderStatus.COMPLETED) {
            return CancellationEligibility.denied(CancellationDenialReason.ORDER_ALREADY_COMPLETED);
        }
        // Spec §35: if shipped/delivered, deny and (at the conversation layer, not here) suggest a return instead.
        if (order.getFulfillmentStatus() != FulfillmentStatus.UNFULFILLED) {
            return CancellationEligibility.denied(CancellationDenialReason.ORDER_FULFILLED);
        }

        PaymentConsequence consequence = determineConsequence(payment);
        if (consequence == null) {
            return CancellationEligibility.denied(CancellationDenialReason.PAYMENT_STATE_INCOMPATIBLE);
        }
        return CancellationEligibility.eligible(consequence);
    }

    private PaymentConsequence determineConsequence(Payment payment) {
        return switch (payment.getStatus()) {
            // COD has no captured funds, so there's nothing to refund. A non-COD payment sitting in
            // PENDING (e.g. a bank transfer awaiting confirmation) is genuinely ambiguous - money may or
            // may not be in flight - so it goes to manual review rather than being guessed either way.
            case PENDING -> payment.getMethod() == PaymentMethod.COD
                    ? PaymentConsequence.NO_REFUND_REQUIRED
                    : PaymentConsequence.MANUAL_REVIEW_REQUIRED;
            case AUTHORIZED -> PaymentConsequence.VOID_AUTHORIZATION;
            case PAID -> PaymentConsequence.REFUND_REQUIRED;
            case FAILED -> PaymentConsequence.NO_REFUND_REQUIRED;
            // These imply the payment lifecycle already concluded through some other path (e.g. a prior
            // cancellation/return) - an order in this state being newly cancelled indicates a data
            // inconsistency, so it's treated as denied rather than silently re-processed.
            case VOIDED, PARTIALLY_REFUNDED, REFUNDED -> null;
        };
    }
}