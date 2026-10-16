package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.1: one named fact the conversation has established.
 *
 * @param name       the slot name, always from {@link DialogueSlotNames}
 * @param value      the established value (trimmed, never blank)
 * @param confidence 0.0-1.0; drives the P2.4 clarification policy
 * @param provenance where the value came from (see {@link SlotProvenance})
 * @param turn       the turn number that established this value
 */
public record Slot(String name, String value, double confidence, SlotProvenance provenance, int turn) {

    public Slot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** True when the slot may be used to target a mutation or tool call (P2.4). */
    public boolean usableForMutation() {
        return ClarificationPolicy.usableForMutation(this);
    }
}
