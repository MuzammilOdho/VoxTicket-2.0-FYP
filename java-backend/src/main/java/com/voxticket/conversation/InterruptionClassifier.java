package com.voxticket.conversation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * P2.3: deterministic interruption classification.
 *
 * <p>When a turn is preempted/aborted and the new utterance arrives, this
 * classifies it against the aborted turn's heard text using fixed markers
 * <em>before</em> the model runs. The result is a hint - the LLM still
 * proposes the classification through the {@code updateDialogueState} tool,
 * and {@link #validateProposal} has the final say. Disagreements fall back
 * to {@code NEW_INTENT} (safest: never mis-merge).
 *
 * <p>Marker priority: withdrawal &gt; correction &gt; continuation &gt;
 * new-intent. The "cancel" ambiguity is resolved by dialogue state: "cancel
 * my order" (cancel + an order reference) is a new cancellation request,
 * never a withdrawal; "cancel that" while a cancellation procedure is
 * active belongs to the existing procedure logic, not to withdrawal.
 */
public final class InterruptionClassifier {

    private static final Pattern CORRECTION_MARKERS =
            Pattern.compile("\\b(no|not|n't|i meant|the other|actually|wrong)\\b");
    private static final Pattern CONTINUATION_MARKERS =
            Pattern.compile("^\\s*(and|also|plus)\\b|\\bas well\\b");
    private static final Pattern WITHDRAWAL_MARKERS =
            Pattern.compile("\\b(never mind|forget it|forget that|forget this|drop it|drop that)\\b");
    private static final Pattern CANCEL_THAT_MARKERS =
            Pattern.compile("\\bcancel\\s+(that|it|this)\\b");
    private static final Pattern ORDER_REFERENCE_PATTERN =
            Pattern.compile("[A-Za-z]{2,4}-?\\d{3,}");
    private static final Pattern QUESTION_MARK = Pattern.compile("\\?\\s*$");

    private InterruptionClassifier() {
    }

    /**
     * Deterministic classification of the new utterance against the aborted
     * turn's heard text. Never throws; returns the neutral signal when there
     * is nothing to relate to.
     *
     * @param newText            the new (normalized) user utterance
     * @param state              the session's dialogue state
     * @param abortedAssistantText the aborted turn's repaired heard text, or
     *                             null when the previous turn was not aborted
     */
    public static InterruptionSignal classify(String newText, DialogueState state, String abortedAssistantText) {
        if (newText == null || newText.isBlank() || abortedAssistantText == null) {
            return InterruptionSignal.none();
        }
        String lower = " " + newText.toLowerCase(Locale.ROOT).trim() + " ";

        // Withdrawal first: explicit abandonment markers. "cancel that" only
        // counts as withdrawal when there is something queued to withdraw and
        // no active procedure for the existing control tools to handle.
        boolean explicitWithdrawal = WITHDRAWAL_MARKERS.matcher(lower).find();
        boolean cancelThat = CANCEL_THAT_MARKERS.matcher(lower).find();
        boolean hasOrderRef = ORDER_REFERENCE_PATTERN.matcher(newText).find();
        if (explicitWithdrawal && !hasOrderRef) {
            return new InterruptionSignal(InterruptionClass.WITHDRAWAL, true, "withdrawal-markers", null);
        }
        if (cancelThat && !hasOrderRef && state.hasPendingRequests()) {
            return new InterruptionSignal(InterruptionClass.WITHDRAWAL, true, "cancel-that+pending", null);
        }

        // Correction: a negation/correction marker plus a slot-shaped value
        // (an order/ticket reference) or a value matching a known slot.
        String slotName = findReferencedSlot(newText, state);
        if (CORRECTION_MARKERS.matcher(lower).find() && (hasOrderRef || slotName != null)) {
            String evidence = hasOrderRef ? "negation-marker+reference-pattern"
                    : "negation-marker+known-slot-value";
            return new InterruptionSignal(InterruptionClass.CORRECTION, true, evidence,
                    hasOrderRef ? DialogueSlotNames.ORDER_REFERENCE : slotName);
        }

        // Continuation: additive markers at the start of the utterance.
        if (CONTINUATION_MARKERS.matcher(lower).find()) {
            return new InterruptionSignal(InterruptionClass.CONTINUATION, true, "additive-markers", null);
        }

        return InterruptionSignal.none();
    }

    /**
     * Validates an LLM-proposed classification. Returns the proposed class
     * when it checks out, otherwise {@code NEW_INTENT} (safest fallback).
     *
     * @param proposed the model's proposed class
     * @param slotName the slot the proposal names (for CORRECTION), or null
     * @param state    the session's dialogue state
     */
    public static InterruptionClass validateProposal(InterruptionClass proposed, String slotName,
                                                     DialogueState state) {
        if (proposed == null) {
            return InterruptionClass.NEW_INTENT;
        }
        return switch (proposed) {
            // A correction must name a slot that actually exists - otherwise
            // the model would "correct" something the system never recorded.
            case CORRECTION -> (slotName != null && state.getSlot(slotName).isPresent())
                    ? InterruptionClass.CORRECTION : InterruptionClass.NEW_INTENT;
            // A withdrawal needs something to withdraw.
            case WITHDRAWAL -> state.hasPendingRequests()
                    ? InterruptionClass.WITHDRAWAL : InterruptionClass.NEW_INTENT;
            case CONTINUATION, NEW_INTENT -> proposed;
        };
    }

    /** True when the utterance is phrased as a question. */
    public static boolean isQuestion(String text) {
        return text != null && QUESTION_MARK.matcher(text.trim()).find();
    }

    /**
     * Finds a known slot whose current value appears in the text
     * (alphanumeric-core match, so "ord 10001" matches "ORD-10001").
     */
    private static String findReferencedSlot(String text, DialogueState state) {
        String core = alphanumericCore(text);
        if (core.isEmpty()) {
            return null;
        }
        for (Slot slot : state.getSlots()) {
            String slotCore = alphanumericCore(slot.value());
            if (!slotCore.isEmpty() && core.contains(slotCore)) {
                return slot.name();
            }
        }
        return null;
    }

    /** Lowercased alphanumeric core, for span matching across STT variants. */
    static String alphanumericCore(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
