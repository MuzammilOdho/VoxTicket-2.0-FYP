package com.voxticket.conversation;

/**
 * P2.1: where a dialogue {@link Slot}'s value came from.
 *
 * <p>Provenance decides both trust and precedence:
 * <ul>
 *   <li>{@code TOOL_READ} - the value came from an authoritative tool read
 *       (an owned-order lookup, a ticket lookup). It always wins over
 *       LLM-proposed values and is never settable through the
 *       {@code updateDialogueState} tool.</li>
 *   <li>{@code USER_SAID} - the caller said the value. Accepted only when a
 *       matching span exists in the heard transcript (see
 *       {@link DialogueStateUpdater}); otherwise the proposal is downgraded
 *       to {@code INFERRED}.</li>
 *   <li>{@code INFERRED} - the model guessed or normalized the value. Java
 *       caps its confidence at 0.7, so an inference can never silently drive
 *       a mutation.</li>
 * </ul>
 */
public enum SlotProvenance {
    USER_SAID,
    TOOL_READ,
    INFERRED
}
