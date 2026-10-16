package com.voxticket.conversation;

/**
 * P2.1: the kinds of discourse obligation the system can owe the caller.
 *
 * <p>Obligations are created deterministically and discharged on
 * fulfillment - they replace ad-hoc "remember to..." logic:
 * <ul>
 *   <li>{@code ANSWER_QUESTION} - the caller asked a fresh question while
 *       other work was pending; the reply owes an answer before the pending
 *       work resumes.</li>
 *   <li>{@code CONFIRM_BEFORE_MUTATE} - a mutation target came from a slot
 *       with middling confidence; the system owes a value-naming
 *       confirmation before executing.</li>
 *   <li>{@code RESUME_DEFERRED} - a request was queued on the pending stack;
 *       the system owes resuming it when the active work finishes.</li>
 * </ul>
 */
public enum ObligationType {
    ANSWER_QUESTION,
    CONFIRM_BEFORE_MUTATE,
    RESUME_DEFERRED
}
