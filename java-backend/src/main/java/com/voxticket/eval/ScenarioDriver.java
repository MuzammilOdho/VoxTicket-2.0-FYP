package com.voxticket.eval;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ClarificationPolicy;
import com.voxticket.conversation.ConversationLanguage;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.CorrectionAntecedent;
import com.voxticket.conversation.DialogueState;
import com.voxticket.conversation.DirectProcedureResponseRenderer;
import com.voxticket.conversation.InterruptionClassifier;
import com.voxticket.conversation.InterruptionSignal;
import com.voxticket.conversation.Slot;
import com.voxticket.procedure.DeferredProcedureIntent;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureOutcome;
import com.voxticket.procedure.ProcedureState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P3.3: drives one eval scenario's scripted turns through the deterministic
 * core (procedure layer + P2 dialogue state) and snapshots the
 * {@link ObservedOutcome} after each step.
 *
 * <p>The driver stands in for the LLM's tool-call proposals: the scenario
 * author scripts which procedure/dialogue actions each user text triggers,
 * exactly as the runtime would after the model proposes them. Everything
 * the driver touches is deterministic Java - the assertions therefore test
 * the system's behavior, not the model's wording.
 *
 * <p>Mutations are counted, not executed for real: the test wires the
 * service mocks' answers to call {@link #noteMutation(String)}, so a
 * scenario can assert a mutation ran exactly once - or never.
 */
public final class ScenarioDriver {

    private final ConversationSession session;
    private final ProcedureCoordinator coordinator;
    private final DirectProcedureResponseRenderer renderer;

    private final List<String> startedProcedures = new ArrayList<>();
    private final List<String> completedProcedures = new ArrayList<>();
    private final List<String> mutationsExecuted = new ArrayList<>();
    private String lastOutcomeCode;
    private String renderedReply;
    private String assertedSlot = "";

    public ScenarioDriver(ConversationSession session,
                          ProcedureCoordinator coordinator,
                          DirectProcedureResponseRenderer renderer) {
        this.session = session;
        this.coordinator = coordinator;
        this.renderer = renderer;
    }

    /** A fresh CHAT session driver. */
    public static ScenarioDriver chat(ProcedureCoordinator coordinator, DirectProcedureResponseRenderer renderer) {
        return new ScenarioDriver(
                ConversationSession.newSession("eval-" + System.nanoTime(), Channel.CHAT),
                coordinator, renderer);
    }

    public ConversationSession session() {
        return session;
    }

    public DialogueState dialogueState() {
        return session.getDialogueState();
    }

    /** Records the caller's text on the session (redaction is the runtime's job, not the driver's). */
    public ScenarioDriver userSays(String text) {
        session.recordUserMessage(text);
        return this;
    }

    /** Test hook: the service mocks call this when they "execute" a mutation. */
    public void noteMutation(String name) {
        mutationsExecuted.add(name);
    }

    /** Renders deterministic copy for the outcome, when the scenario wants to assert on it. */
    public ScenarioDriver render(ProcedureOutcome outcome) {
        this.renderedReply = renderer.render(ConversationLanguage.ENGLISH, outcome);
        return this;
    }

    private ProcedureOutcome track(ProcedureOutcome outcome) {
        this.lastOutcomeCode = outcome.code();
        return outcome;
    }

    public ProcedureOutcome startCancellation(String orderReference) {
        ProcedureOutcome outcome = track(coordinator.startCancellation(session, orderReference));
        session.getActiveProcedure().ifPresent(p -> startedProcedures.add(p.getType().name()));
        return outcome;
    }

    public ProcedureOutcome submitCode(String code) {
        String before = session.getActiveProcedure().map(p -> p.getType().name()).orElse(null);
        ProcedureOutcome outcome = track(coordinator.submitVerificationCode(session, code));
        if (before != null && isTerminalSuccess(outcome.code())) {
            completedProcedures.add(before);
        }
        return outcome;
    }

    public ProcedureOutcome confirm() {
        String before = session.getActiveProcedure().map(p -> p.getType().name()).orElse(null);
        ProcedureOutcome outcome = track(coordinator.confirmActive(session));
        if (before != null && isTerminalSuccess(outcome.code())) {
            completedProcedures.add(before);
        }
        return outcome;
    }

    public ProcedureOutcome resend(String idempotencyKey) {
        return track(coordinator.resendVerificationCode(session, idempotencyKey));
    }

    public InterruptionSignal classifyInterruption(String newText, String abortedAssistantText) {
        return InterruptionClassifier.classify(newText, dialogueState(), abortedAssistantText);
    }

    /** Records a correction antecedent (P2.3): what the caller corrected, and what it was. */
    public void recordCorrection(String slotName, String oldValue, int turn) {
        dialogueState().recordCorrection(new CorrectionAntecedent(slotName, oldValue, turn));
    }

    public void pushPending(DeferredProcedureIntent intent) {
        dialogueState().pushPendingRequest(intent);
    }

    public Optional<DeferredProcedureIntent> popPending() {
        return dialogueState().popPendingRequest();
    }

    public Optional<ProcedureOutcome> promotePending() {
        return coordinator.promoteDeferredIntent(session);
    }

    public void setSlot(Slot slot) {
        dialogueState().setSlot(slot);
    }

    /** Runs the P2.4 policy for a slot and remembers it for strike assertions. */
    public ClarificationPolicy.Decision checkClarification(String slotName) {
        this.assertedSlot = slotName;
        return dialogueState().getSlot(slotName)
                .map(ClarificationPolicy::decide)
                .orElse(ClarificationPolicy.Decision.OPEN_CLARIFICATION);
    }

    /** Renders the P2.4 clarification copy for a slot. */
    public ScenarioDriver renderClarification(String slotName) {
        this.assertedSlot = slotName;
        this.renderedReply = dialogueState().getSlot(slotName)
                .map(slot -> ClarificationPolicy.decide(slot) == ClarificationPolicy.Decision.TARGETED_CLARIFICATION
                        ? ClarificationPolicy.renderTargetedClarification(slot)
                        : ClarificationPolicy.renderOpenClarification(slot))
                .orElse(null);
        return this;
    }

    /** Records one low-confidence observation; returns the strike count. */
    public int recordStrike(String slotName, double confidence) {
        this.assertedSlot = slotName;
        return dialogueState().recordSlotConfidence(slotName, confidence);
    }

    public ObservedOutcome observe() {
        Optional<ProcedureState> active = session.getActiveProcedure();
        Map<String, String> slots = new LinkedHashMap<>();
        dialogueState().getSlots().forEach(slot -> slots.put(slot.name(), slot.value()));
        return new ObservedOutcome(
                active.map(p -> p.getType().name()).orElse(null),
                active.map(p -> p.getStatus().name()).orElse(null),
                List.copyOf(startedProcedures),
                List.copyOf(completedProcedures),
                dialogueState().pendingRequestCount(),
                Map.copyOf(slots),
                lastOutcomeCode,
                List.copyOf(mutationsExecuted),
                renderedReply,
                dialogueState().getCorrectionAntecedents().size(),
                assertedSlot.isEmpty() ? 0 : dialogueState().slotStrikes(assertedSlot));
    }

    private static boolean isTerminalSuccess(String code) {
        return "CANCELLED".equals(code) || "RETURN_STARTED".equals(code) || "CLAIM_FILED".equals(code);
    }
}
