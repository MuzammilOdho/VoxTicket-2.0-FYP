package com.voxticket.identity;

/**
 * Thrown when an operation requires a higher IdentityAssurance than the
 * caller currently has. Phase 9 (OTP) is what lets a caller step up from
 * PHONE_MATCHED to OTP_VERIFIED mid-conversation; this is what a higher
 * layer (Phase 4+ ConversationRuntime) will catch to trigger that flow
 * instead of just failing the turn.
 */
public class InsufficientAssuranceException extends RuntimeException {

    private final IdentityAssurance required;
    private final IdentityAssurance actual;

    public InsufficientAssuranceException(IdentityAssurance required, IdentityAssurance actual) {
        super("Operation requires " + required + " but caller has " + actual);
        this.required = required;
        this.actual = actual;
    }

    public IdentityAssurance getRequired() {
        return required;
    }

    public IdentityAssurance getActual() {
        return actual;
    }
}