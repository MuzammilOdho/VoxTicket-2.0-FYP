package com.voxticket.conversation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * P2.1: the explicit dialogue state for one conversation session.
 *
 * <p>Owned by {@link ConversationSession}. Answers "what does the system
 * think the caller wants right now?" as data instead of scattering intent
 * across guarded modes, focus, and flags:
 * <ul>
 *   <li><b>pendingRequests</b> (P2.2): bounded LIFO stack of unfinished
 *       caller requests ({@code DeferredProcedureIntent} elements, max
 *       depth {@link #MAX_PENDING_REQUESTS}).</li>
 *   <li><b>slots</b>: named facts with confidence + provenance, driving the
 *       P2.4 clarification policy.</li>
 *   <li><b>obligations</b>: what the system owes the caller
 *       (answer / confirm-before-mutate / resume-deferred).</li>
 *   <li><b>correctionAntecedents</b>: what past corrections corrected.</li>
 *   <li><b>lowConfidenceStrikes</b>: consecutive low-confidence sets per
 *       slot; the 3rd triggers the human-handoff offer (P2.4).</li>
 *   <li><b>lastInterruptionSignal</b>: the deterministic P2.3 signal for the
 *       current turn, or null when the previous turn was not aborted.</li>
 * </ul>
 *
 * <p>All production mutation happens inside
 * {@code SessionStore.withSession}'s per-session ownership, so no extra
 * synchronization is needed here. The LLM never touches this object
 * directly - it proposes through the {@code updateDialogueState} tool and
 * {@link DialogueStateUpdater} validates and applies.
 */
public final class DialogueState {

    /** P2.2: pending-request stack max depth (approved default). */
    public static final int MAX_PENDING_REQUESTS = 3;
    /** P2.4: consecutive low-confidence sets before the handoff offer. */
    public static final int LOW_CONFIDENCE_STRIKE_LIMIT = 3;

    private static final int MAX_SLOTS = 24;
    private static final int MAX_OBLIGATIONS = 8;
    private static final int MAX_CORRECTION_ANTECEDENTS = 10;

    private final Deque<com.voxticket.procedure.DeferredProcedureIntent> pendingRequests = new ArrayDeque<>();
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Deque<Obligation> obligations = new ArrayDeque<>();
    private final Deque<CorrectionAntecedent> correctionAntecedents = new ArrayDeque<>();
    private final Map<String, Integer> lowConfidenceStrikes = new LinkedHashMap<>();
    private InterruptionSignal lastInterruptionSignal;

    // ---- pending-request stack (P2.2) ----

    /**
     * Pushes a request onto the stack (top = most recent).
     *
     * @throws IllegalStateException when the stack is already at
     *         {@link #MAX_PENDING_REQUESTS}
     */
    public void pushPendingRequest(com.voxticket.procedure.DeferredProcedureIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (pendingRequests.size() >= MAX_PENDING_REQUESTS) {
            throw new IllegalStateException(
                    "Pending-request stack is full (" + MAX_PENDING_REQUESTS + "); ask the caller to pick one");
        }
        pendingRequests.addLast(intent);
    }

    /** Pops the top of the stack; empty when nothing is queued. */
    public Optional<com.voxticket.procedure.DeferredProcedureIntent> popPendingRequest() {
        return Optional.ofNullable(pendingRequests.pollLast());
    }

    /** The top of the stack without removing it; empty when nothing is queued. */
    public Optional<com.voxticket.procedure.DeferredProcedureIntent> peekPendingRequest() {
        return Optional.ofNullable(pendingRequests.peekLast());
    }

    /** All queued requests, top first. */
    public List<com.voxticket.procedure.DeferredProcedureIntent> getPendingRequests() {
        List<com.voxticket.procedure.DeferredProcedureIntent> topFirst = new ArrayList<>(pendingRequests);
        java.util.Collections.reverse(topFirst);
        return List.copyOf(topFirst);
    }

    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    public boolean hasPendingRequests() {
        return !pendingRequests.isEmpty();
    }

    // ---- slots ----

    /** Stores (or replaces) a slot. Bounded: oldest entries evict first. */
    public void setSlot(Slot slot) {
        Objects.requireNonNull(slot, "slot");
        slots.put(slot.name(), slot);
        while (slots.size() > MAX_SLOTS) {
            slots.remove(slots.keySet().iterator().next());
        }
    }

    public Optional<Slot> getSlot(String name) {
        return Optional.ofNullable(slots.get(name));
    }

    public void clearSlot(String name) {
        slots.remove(name);
    }

    /** All slots, insertion-ordered. */
    public List<Slot> getSlots() {
        return List.copyOf(slots.values());
    }

    // ---- obligations ----

    /** Adds an obligation. Bounded: oldest entries evict first. */
    public void addObligation(ObligationType type, String detail, int turn) {
        obligations.addLast(new Obligation(type, detail, turn));
        while (obligations.size() > MAX_OBLIGATIONS) {
            obligations.removeFirst();
        }
    }

    /**
     * Discharges the oldest obligation of the given type. Returns true when
     * one was discharged.
     */
    public boolean dischargeObligation(ObligationType type) {
        for (Obligation obligation : obligations) {
            if (obligation.type() == type) {
                obligations.remove(obligation);
                return true;
            }
        }
        return false;
    }

    /**
     * Discharges the oldest obligation of the given type whose detail
     * contains the given text. Returns true when one was discharged.
     */
    public boolean dischargeObligation(ObligationType type, String detailContains) {
        for (Obligation obligation : obligations) {
            if (obligation.type() == type && obligation.detail().contains(detailContains)) {
                obligations.remove(obligation);
                return true;
            }
        }
        return false;
    }

    public boolean hasObligation(ObligationType type) {
        return obligations.stream().anyMatch(o -> o.type() == type);
    }

    /** All outstanding obligations, oldest first. */
    public List<Obligation> getObligations() {
        return List.copyOf(obligations);
    }

    // ---- correction antecedents ----

    /** Records what a correction corrected. Bounded: oldest entries evict first. */
    public void recordCorrection(CorrectionAntecedent antecedent) {
        Objects.requireNonNull(antecedent, "antecedent");
        correctionAntecedents.addLast(antecedent);
        while (correctionAntecedents.size() > MAX_CORRECTION_ANTECEDENTS) {
            correctionAntecedents.removeFirst();
        }
    }

    /** Correction antecedents, most recent first. */
    public List<CorrectionAntecedent> getCorrectionAntecedents() {
        List<CorrectionAntecedent> recentFirst = new ArrayList<>(correctionAntecedents);
        java.util.Collections.reverse(recentFirst);
        return List.copyOf(recentFirst);
    }

    // ---- low-confidence strikes (P2.4) ----

    /**
     * Records a low-confidence set of the slot; returns the new consecutive
     * count. Any set at or above 0.8 resets the count to zero.
     */
    public int recordSlotConfidence(String slotName, double confidence) {
        if (confidence >= ClarificationPolicy.ACT_DIRECTLY_THRESHOLD) {
            lowConfidenceStrikes.remove(slotName);
            return 0;
        }
        int strikes = lowConfidenceStrikes.getOrDefault(slotName, 0) + 1;
        lowConfidenceStrikes.put(slotName, strikes);
        return strikes;
    }

    /** Resets the strike count, e.g. after the caller confirms the slot. */
    public void resetSlotStrikes(String slotName) {
        lowConfidenceStrikes.remove(slotName);
    }

    public int slotStrikes(String slotName) {
        return lowConfidenceStrikes.getOrDefault(slotName, 0);
    }

    // ---- interruption signal (P2.3) ----

    /** The deterministic interruption signal for the current turn, or null. */
    public InterruptionSignal getLastInterruptionSignal() {
        return lastInterruptionSignal;
    }

    public void setLastInterruptionSignal(InterruptionSignal signal) {
        this.lastInterruptionSignal = signal;
    }

    public void clearLastInterruptionSignal() {
        this.lastInterruptionSignal = null;
    }
}
