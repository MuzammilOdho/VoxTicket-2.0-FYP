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
    /** The return procedure needs a valid quantity (positive, within the returnable limit). */
    ASK_QUANTITY,
    /** The queue is full, or a queued request blocks the freed slot; the customer must choose what happens next. */
    ASK_PROCEDURE_CHOICE,
    /** A request was deferred behind the live procedure; the customer should complete the active one first. */
    COMPLETE_ACTIVE_PROCEDURE,
    /** The request cannot proceed until the customer's identity is verified. */
    VERIFY_IDENTITY,
    /** A procedure request was denied as ineligible; the model should consult the authoritative support context for what is actually available. */
    CHECK_SUPPORT_OPTIONS,
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
            case "QUANTITY_REQUIRED" -> ASK_QUANTITY;
            // Pass 2D-B: the two-slot TOO_MANY_ACTIVE_PROCEDURES model is
            // gone; a full queue (active + deferred + third request) is
            // PENDING_REQUEST_LIMIT_REACHED, and a new request contesting a
            // queued intent after the active slot was freed is
            // DEFERRED_REQUEST_PENDING. Both need an explicit customer
            // choice and change nothing.
            case "PENDING_REQUEST_LIMIT_REACHED", "DEFERRED_REQUEST_PENDING" -> ASK_PROCEDURE_CHOICE;
            case "PROCEDURE_DEFERRED", "ALREADY_DEFERRED" -> COMPLETE_ACTIVE_PROCEDURE;
            case "IDENTITY_NOT_VERIFIED" -> VERIFY_IDENTITY;
            // An eligibility denial is not a dead end: the authoritative
            // support context (getMyOrderContext) establishes what actually
            // is available, so the model is pointed there rather than left
            // with no defined next step.
            case "NOT_ELIGIBLE" -> CHECK_SUPPORT_OPTIONS;
            case "VERIFICATION_RATE_LIMITED" -> RETRY_LATER;
            // ALREADY_PENDING is resolved by the mapper from the pendingStage
            // metadata, because its next action depends on the live
            // procedure's stage (code vs. confirmation).
            // NOT_FOUND_FOR_ACCOUNT, ESCALATED, ALREADY_ESCALATED,
            // ACTIVE_PROCEDURE_ABANDONED, DEFERRED_INTENT_DISCARDED, and every
            // unknown code deliberately fall through to NONE.
            default -> NONE;
        };
    }
}
