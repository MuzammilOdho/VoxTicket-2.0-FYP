package com.voxticket.conversation;

/**
 * P2.1: the actions the {@code updateDialogueState} tool accepts.
 *
 * <p>The schema is deliberately narrow - the LLM proposes, deterministic
 * Java validates and applies. No action here executes a commerce mutation,
 * issues OTP, or touches business state.
 */
public enum DialogueStateAction {
    /** Set (or replace) a slot: slotName, slotValue, confidence?, provenance?. */
    SET_SLOT,
    /** The caller confirmed a slot's value: slotName. Confidence becomes 1.0. */
    CONFIRM_SLOT,
    /** The caller corrected a slot: slotName, slotValue, confidence?, provenance?. */
    MARK_CORRECTION,
    /** Queue a procedure request: procedureType, orderNumber. */
    PUSH_REQUEST,
    /** Discard the top queued request (withdrawal). */
    POP_REQUEST,
    /** Propose how the new utterance relates to the aborted turn:
     * interruptionClass, slotName? (for CORRECTION), slotValue? (new value). */
    CLASSIFY_INTERRUPTION
}
