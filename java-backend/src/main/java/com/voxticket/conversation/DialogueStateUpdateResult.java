package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.1: the validated outcome of one {@code updateDialogueState} proposal.
 *
 * @param ok      true when the proposal was applied (possibly adjusted)
 * @param message customer-safe summary for the model: what was recorded,
 *                what was adjusted and why, and any clarification copy the
 *                model should speak next
 */
public record DialogueStateUpdateResult(boolean ok, String message) {

    public DialogueStateUpdateResult {
        Objects.requireNonNull(message, "message");
    }

    public static DialogueStateUpdateResult applied(String message) {
        return new DialogueStateUpdateResult(true, message);
    }

    public static DialogueStateUpdateResult rejected(String message) {
        return new DialogueStateUpdateResult(false, message);
    }
}
