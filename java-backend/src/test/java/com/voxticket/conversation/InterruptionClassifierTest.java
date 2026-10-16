package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.procedure.DeferredProcedureIntent;
import com.voxticket.procedure.ProcedureType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P2.3: deterministic interruption-classification fixtures plus
 * LLM-proposal validation fallbacks.
 */
class InterruptionClassifierTest {

    private DialogueState stateWithSlot(String orderNumber) {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("my order is " + orderNumber);
        session.getDialogueState().setSlot(
                new Slot(DialogueSlotNames.ORDER_REFERENCE, orderNumber, 0.9, SlotProvenance.USER_SAID, 1));
        return session.getDialogueState();
    }

    private DialogueState stateWithPendingRequest() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.getDialogueState().pushPendingRequest(new DeferredProcedureIntent(
                ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                null, null, null, "1", null, Instant.now()));
        return session.getDialogueState();
    }

    @Test
    void correctionMarkerWithReferenceClassifiesCorrection() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionSignal signal = InterruptionClassifier.classify(
                "no, order ORD-10001 not 10002", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.CORRECTION);
        assertThat(signal.deterministic()).isTrue();
    }

    @Test
    void iMeantMarkerWithReferenceClassifiesCorrection() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionSignal signal = InterruptionClassifier.classify(
                "no, I meant ORD-10003", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.CORRECTION);
        assertThat(signal.deterministic()).isTrue();
        assertThat(signal.slotName()).isEqualTo(DialogueSlotNames.ORDER_REFERENCE);
    }

    @Test
    void correctionMarkerWithNoResolvableValueStaysNewIntent() {
        DialogueState state = stateWithSlot("ORD-10002");

        // "the other one" names no value the system can resolve - the safe
        // fallback is NEW_INTENT; the model proposes the real correction
        // through the state-update tool and Java validates it.
        InterruptionSignal signal = InterruptionClassifier.classify(
                "no, the other one", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.NEW_INTENT);
        assertThat(signal.deterministic()).isFalse();
    }

    @Test
    void additiveMarkerClassifiesContinuation() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionSignal signal = InterruptionClassifier.classify(
                "and also the charger", state, "So I'll start a return.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.CONTINUATION);
        assertThat(signal.deterministic()).isTrue();
    }

    @Test
    void unrelatedUtteranceClassifiesNewIntent() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionSignal signal = InterruptionClassifier.classify(
                "what's your return policy", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.NEW_INTENT);
        assertThat(signal.deterministic()).isFalse();
    }

    @Test
    void neverMindClassifiesWithdrawal() {
        DialogueState state = stateWithPendingRequest();

        InterruptionSignal signal = InterruptionClassifier.classify(
                "never mind, forget it", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.WITHDRAWAL);
        assertThat(signal.deterministic()).isTrue();
    }

    @Test
    void cancelMyOrderIsNotAWithdrawal() {
        DialogueState state = stateWithPendingRequest();

        // "cancel my order" is a new cancellation request (handled by the
        // procedure tools), never a withdrawal of the queued request.
        InterruptionSignal signal = InterruptionClassifier.classify(
                "cancel my order", state, "So I'll check the status.");

        assertThat(signal.interruptionClass()).isNotEqualTo(InterruptionClass.WITHDRAWAL);
    }

    @Test
    void cancelThatWithPendingRequestClassifiesWithdrawal() {
        DialogueState state = stateWithPendingRequest();

        InterruptionSignal signal = InterruptionClassifier.classify(
                "no wait, cancel that", state, "So I'll cancel order ORD-10002.");

        assertThat(signal.interruptionClass()).isEqualTo(InterruptionClass.WITHDRAWAL);
        assertThat(signal.deterministic()).isTrue();
    }

    @Test
    void noAbortedTurnMeansNeutralSignal() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionSignal signal = InterruptionClassifier.classify("no, the other one", state, null);

        assertThat(signal).isEqualTo(InterruptionSignal.none());
    }

    // ---- proposal validation: disagreements fall back to NEW_INTENT ----

    @Test
    void correctionOfNonexistentSlotFallsBackToNewIntent() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionClass validated = InterruptionClassifier.validateProposal(
                InterruptionClass.CORRECTION, DialogueSlotNames.TICKET_REFERENCE, state);

        assertThat(validated).isEqualTo(InterruptionClass.NEW_INTENT);
    }

    @Test
    void correctionOfExistingSlotIsAccepted() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionClass validated = InterruptionClassifier.validateProposal(
                InterruptionClass.CORRECTION, DialogueSlotNames.ORDER_REFERENCE, state);

        assertThat(validated).isEqualTo(InterruptionClass.CORRECTION);
    }

    @Test
    void withdrawalWithNoPendingRequestFallsBackToNewIntent() {
        DialogueState state = stateWithSlot("ORD-10002");

        InterruptionClass validated = InterruptionClassifier.validateProposal(
                InterruptionClass.WITHDRAWAL, null, state);

        assertThat(validated).isEqualTo(InterruptionClass.NEW_INTENT);
    }

    @Test
    void withdrawalWithPendingRequestIsAccepted() {
        DialogueState state = stateWithPendingRequest();

        InterruptionClass validated = InterruptionClassifier.validateProposal(
                InterruptionClass.WITHDRAWAL, null, state);

        assertThat(validated).isEqualTo(InterruptionClass.WITHDRAWAL);
    }

    @Test
    void classificationProposalFlowsThroughTheUpdater() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("my order is ORD-10001");
        session.recordUserMessage("no, ORD-10002");
        DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.SET_SLOT, DialogueSlotNames.ORDER_REFERENCE,
                        "ORD-10001", 0.9, "USER_SAID", null, null, null),
                (s, t, o) -> "queued");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.CLASSIFY_INTERRUPTION, DialogueSlotNames.ORDER_REFERENCE,
                        "ORD-10002", 0.9, "USER_SAID", null, null, "CORRECTION"),
                (s, t, o) -> "queued");

        assertThat(result.ok()).isTrue();
        assertThat(session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow().value())
                .isEqualTo("ORD-10002");
        assertThat(session.getDialogueState().getCorrectionAntecedents()).hasSize(1);
    }

    @Test
    void withdrawalProposalPopsTheStackThroughTheUpdater() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.getDialogueState().pushPendingRequest(new DeferredProcedureIntent(
                ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                null, null, null, "1", null, Instant.now()));

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.CLASSIFY_INTERRUPTION, null,
                        null, null, null, null, null, "WITHDRAWAL"),
                (s, t, o) -> "queued");

        assertThat(result.ok()).isTrue();
        assertThat(session.getDialogueState().hasPendingRequests()).isFalse();
    }
}
