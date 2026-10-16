package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.1: one {@code updateDialogueState} tool proposal.
 *
 * <p>A single flat record keeps the tool schema strict and auditable. Only
 * the fields the action needs are read; the rest must be null. Java
 * validates every field - see {@link DialogueStateUpdater}.
 */
public record DialogueStateProposal(
        DialogueStateAction action,
        String slotName,
        String slotValue,
        Double confidence,
        String provenance,
        String procedureType,
        String orderNumber,
        String interruptionClass) {

    public DialogueStateProposal {
        Objects.requireNonNull(action, "action");
    }

    /** Convenience factory for the common set-slot proposal. */
    public static DialogueStateProposal setSlot(String slotName, String slotValue,
                                                Double confidence, String provenance) {
        return new DialogueStateProposal(DialogueStateAction.SET_SLOT, slotName, slotValue,
                confidence, provenance, null, null, null);
    }
}
