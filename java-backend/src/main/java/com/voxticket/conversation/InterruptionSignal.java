package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.3: the deterministic interruption signal computed at turn start.
 *
 * <p>When a turn was preempted/aborted and a new utterance arrives, Java
 * classifies it against the aborted turn's heard text using fixed markers
 * <em>before</em> the model runs. The signal is a hint rendered into the
 * prompt - the LLM still proposes the classification through the
 * {@code updateDialogueState} tool, and
 * {@link InterruptionClassifier#validateProposal} has the final say.
 *
 * @param interruptionClass the detected class
 * @param deterministic     true when marker evidence fired (as opposed to
 *                          the NEW_INTENT default)
 * @param evidence          short machine-readable note, e.g.
 *                          "negation-marker+order-pattern" or "no-markers"
 * @param slotName          the slot the evidence points at, if any
 */
public record InterruptionSignal(InterruptionClass interruptionClass, boolean deterministic,
                                String evidence, String slotName) {

    public InterruptionSignal {
        Objects.requireNonNull(interruptionClass, "interruptionClass");
        Objects.requireNonNull(evidence, "evidence");
    }

    /** The neutral signal: no markers, no aborted turn to relate to. */
    public static InterruptionSignal none() {
        return new InterruptionSignal(InterruptionClass.NEW_INTENT, false, "no-markers", null);
    }
}
