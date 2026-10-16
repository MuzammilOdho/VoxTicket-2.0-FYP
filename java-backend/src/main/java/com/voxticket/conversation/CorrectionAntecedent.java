package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.1: what a correction corrected.
 *
 * <p>When the caller corrects a slot ("no, order 1234, not 5678"), the old
 * value is recorded here so the next turn can distinguish "the caller
 * changed the order number" from "the caller gave a new order number".
 *
 * @param slotName the corrected slot's name
 * @param oldValue the value before the correction
 * @param turn     the turn that made the correction
 */
public record CorrectionAntecedent(String slotName, String oldValue, int turn) {

    public CorrectionAntecedent {
        Objects.requireNonNull(slotName, "slotName");
        Objects.requireNonNull(oldValue, "oldValue");
    }
}
