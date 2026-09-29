package com.voxticket.procedure;

/**
 * Pass 2B: the small stable vocabulary of model-visible next states for a
 * procedure tool result. The code itself communicates the next state - no
 * prose instructions are attached. Unknown or unexpected coordinator codes
 * map to {@link #NONE}, never to an invented action.
 */
public enum ProcedureNextAction {

    /** The customer must supply a verification (OTP) code next. */
    ASK_VERIFICATION_CODE,
    /** A pending server-controlled confirmation awaits a yes/no decision. */
    ASK_CONFIRMATION,
    /** The procedure needs an item reference that resolves to one order item. */
    ASK_ITEM,
    /** The return procedure needs the customer's return reason. */
    ASK_RETURN_REASON,
    /** The claim procedure needs the customer's problem description. */
    ASK_PROBLEM_DESCRIPTION,
    /** Two procedures are already active; the customer must choose which to continue. */
    ASK_PROCEDURE_CHOICE,
    /** The request cannot proceed until the customer's identity is verified. */
    VERIFY_IDENTITY,
    /** Verification is rate limited; the customer should try again later. */
    RETRY_LATER,
    /** No further model-driven action applies to this state. */
    NONE;

    /**
     * Maps a stable {@link ProcedureOutcome} code to its next action. This is a
     * pure code-to-code mapping: the natural-language {@code message()} of the
     * outcome is never consulted.
     */
    public static ProcedureNextAction fromOutcomeCode(String code) {
        if (code == null) {
            return NONE;
        }
        return switch (code) {
            case "VERIFICATION_REQUIRED" -> ASK_VERIFICATION_CODE;
            case "CONFIRMATION_REQUIRED" -> ASK_CONFIRMATION;
            case "ITEM_REQUIRED" -> ASK_ITEM;
            case "REASON_REQUIRED" -> ASK_RETURN_REASON;
            case "PROBLEM_REQUIRED" -> ASK_PROBLEM_DESCRIPTION;
            case "TOO_MANY_ACTIVE_PROCEDURES" -> ASK_PROCEDURE_CHOICE;
            case "IDENTITY_NOT_VERIFIED" -> VERIFY_IDENTITY;
            case "VERIFICATION_RATE_LIMITED" -> RETRY_LATER;
            // NOT_FOUND_FOR_ACCOUNT, NOT_ELIGIBLE, ESCALATED, ALREADY_ESCALATED,
            // and every unknown code deliberately fall through to NONE.
            default -> NONE;
        };
    }
}
