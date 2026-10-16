package com.voxticket.conversation;

import java.util.Optional;

/**
 * P2.4: confidence-based clarification + confirmation policy.
 *
 * <p>A fixed policy over every slot's confidence and provenance - the system
 * never guesses silently on a shaky slot and never interrogates the caller
 * forever:
 * <ul>
 *   <li><b>confidence &ge; 0.8</b> or provenance {@code TOOL_READ}: act
 *       directly (existing mutation confirmations still apply).</li>
 *   <li><b>0.5 &le; confidence &lt; 0.8</b>: targeted full-sentence
 *       clarification naming the ambiguity - never a bare "which order?".</li>
 *   <li><b>confidence &lt; 0.5</b>: open clarification, and the slot is
 *       <em>not</em> usable for any mutation or tool call until confirmed.</li>
 * </ul>
 * Mutations whose target came from a clarified slot get a value-naming
 * confirmation. The 3rd consecutive low-confidence set on the same slot
 * offers a human handoff instead of another clarification round.
 */
public final class ClarificationPolicy {

    /** At or above: act directly. */
    public static final double ACT_DIRECTLY_THRESHOLD = 0.8;
    /** At or above (and below 0.8): targeted clarification. Below: open. */
    public static final double TARGETED_CLARIFICATION_THRESHOLD = 0.5;
    /** Java caps LLM-proposed INFERRED confidence here (P2.4). */
    public static final double INFERRED_CONFIDENCE_CAP = 0.7;

    /** The policy decision for one slot. */
    public enum Decision {
        ACT_DIRECTLY,
        TARGETED_CLARIFICATION,
        OPEN_CLARIFICATION
    }

    private ClarificationPolicy() {
    }

    /** The policy decision for one slot. */
    public static Decision decide(Slot slot) {
        if (slot.provenance() == SlotProvenance.TOOL_READ) {
            return Decision.ACT_DIRECTLY;
        }
        if (slot.confidence() >= ACT_DIRECTLY_THRESHOLD) {
            return Decision.ACT_DIRECTLY;
        }
        if (slot.confidence() >= TARGETED_CLARIFICATION_THRESHOLD) {
            return Decision.TARGETED_CLARIFICATION;
        }
        return Decision.OPEN_CLARIFICATION;
    }

    /** True when the slot may target a mutation or tool call. */
    public static boolean usableForMutation(Slot slot) {
        return decide(slot) != Decision.OPEN_CLARIFICATION;
    }

    /**
     * Deterministic mutation gate: when the dialogue state holds a
     * low-confidence slot for the mutation's target reference, the mutation
     * must not proceed. Returns the open-clarification copy when blocked,
     * empty when the mutation may proceed.
     */
    public static Optional<String> checkMutationBlocked(DialogueState state, String slotName, String requestedValue) {
        Optional<Slot> slot = state.getSlot(slotName);
        if (slot.isEmpty()) {
            return Optional.empty();
        }
        Slot s = slot.get();
        if (usableForMutation(s)) {
            return Optional.empty();
        }
        if (requestedValue != null && !requestedValue.isBlank()
                && !s.value().equalsIgnoreCase(requestedValue.trim())) {
            // The model is using a different reference than the shaky slot -
            // the slot is not the basis for this mutation.
            return Optional.empty();
        }
        return Optional.of(renderOpenClarification(s));
    }

    /**
     * Full-sentence targeted clarification naming the ambiguity, e.g.
     * "Just to confirm - the order ending in 0001?"
     */
    public static String renderTargetedClarification(Slot slot) {
        String value = slot.value();
        return switch (slot.name()) {
            case DialogueSlotNames.ORDER_REFERENCE ->
                    "Just to confirm - the order ending in " + digitTail(value, 4) + "?";
            case DialogueSlotNames.TICKET_REFERENCE ->
                    "Just to confirm - ticket " + value + "?";
            case DialogueSlotNames.ITEM_REFERENCE ->
                    "Just to confirm - the item is the " + value + "?";
            case DialogueSlotNames.PROBLEM_DESCRIPTION ->
                    "Just to confirm - the problem is: " + value + "?";
            case DialogueSlotNames.RETURN_REASON ->
                    "Just to confirm - you're returning it because " + humanizeEnum(value) + "?";
            case DialogueSlotNames.RETURN_QUANTITY ->
                    "Just to confirm - " + value + " of them?";
            default -> "Just to confirm - " + DialogueSlotNames.describe(slot.name()) + " " + value + "?";
        };
    }

    /** Open clarification for a slot too shaky to name a value for. */
    public static String renderOpenClarification(Slot slot) {
        return "I want to make sure I get this exactly right - could you repeat the "
                + DialogueSlotNames.describe(slot.name()) + " for me?";
    }

    /**
     * Value-naming confirmation for a mutation whose target came from a
     * clarified slot: the value is always named, never assumed silently.
     * The action is a verb phrase like "cancel" or "start a return for".
     */
    public static String renderValueConfirmation(String actionDescription, String orderNumber) {
        return "I'll " + actionDescription + " order " + orderNumber + " - is that correct?";
    }

    /** The 3-strikes human-handoff offer (full-sentence style). */
    public static String renderHandoffOffer(String slotName) {
        return "I'm having trouble getting the " + DialogueSlotNames.describe(slotName)
                + " exactly right - would you like me to connect you with a human agent who can help?";
    }

    private static String digitTail(String value, int length) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() <= length) {
            return digits;
        }
        return digits.substring(digits.length() - length);
    }

    private static String humanizeEnum(String enumName) {
        String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
