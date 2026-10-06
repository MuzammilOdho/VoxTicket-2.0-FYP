package com.voxticket.policy;

/** Spec §35/§28. What happens to the payment if a cancellation goes ahead. */
public enum PaymentConsequence {
    NO_REFUND_REQUIRED,
    VOID_AUTHORIZATION,
    REFUND_REQUIRED,
    /** Spec §35: "PENDING -> follow configured business behavior". Default here is escalation to a support ticket rather than guessing. */
    MANUAL_REVIEW_REQUIRED
}