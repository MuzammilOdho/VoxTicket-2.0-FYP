package com.voxticket.verification;

/**
 * Pass 2C (Task 5): stable internal codes for the distinct deterministic
 * {@link VerificationService#issueChallenge} outcomes. The coordinator
 * propagates this code into {@code ProcedureOutcome} metadata so the
 * direct-response renderer can distinguish "code sent" from "wait briefly"
 * from "temporarily unavailable" without parsing English text.
 */
public enum VerificationIssueCode {
    /** A fresh challenge was issued (and its code sent to the customer). */
    CHALLENGE_ISSUED,
    /** A code was already sent recently - the customer must wait before resending. */
    RESEND_COOLDOWN,
    /** Too many challenges were created for this conversation. */
    SESSION_RATE_LIMITED,
    /** Too many challenges were created for this customer recently. */
    CUSTOMER_RATE_LIMITED
}
