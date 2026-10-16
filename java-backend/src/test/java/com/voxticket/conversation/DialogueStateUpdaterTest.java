package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P2.1: proposal-validation tests for {@link DialogueStateUpdater}.
 *
 * <p>Covers: unknown-slot rejection, value-format rejection, the USER_SAID
 * heard-span rule (keep vs downgrade), confidence clamping, the INFERRED
 * 0.7 cap, the TOOL_READ reservation, TOOL_READ precedence, confirmation,
 * and correction antecedents.
 */
class DialogueStateUpdaterTest {

    private static final DialogueStateUpdater.PendingRequestQueuer QUEUER =
            (session, type, orderNumber) -> "queued";

    private ConversationSession sessionWithHeard(String... userTexts) {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (String text : userTexts) {
            session.recordUserMessage(text);
        }
        return session;
    }

    private DialogueStateProposal setSlot(String name, String value, Double confidence, String provenance) {
        return new DialogueStateProposal(DialogueStateAction.SET_SLOT, name, value,
                confidence, provenance, null, null, null);
    }

    @Test
    void unknownSlotIsRejectedAndLogged() {
        ConversationSession session = sessionWithHeard("my order is ORD-10001");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot("favorite_color", "blue", 0.9, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Unknown slot");
        assertThat(session.getDialogueState().getSlot("favorite_color")).isEmpty();
    }

    @Test
    void badlyFormattedValueIsRejected() {
        ConversationSession session = sessionWithHeard("cancel whatever");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "not-an-order", 0.9, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("not a valid order number");
        assertThat(session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE)).isEmpty();
    }

    @Test
    void userSaidWithHeardSpanKeepsProvenanceAndConfidence() {
        ConversationSession session = sessionWithHeard("I want to cancel my order ORD-10001 please");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.9, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isTrue();
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.provenance()).isEqualTo(SlotProvenance.USER_SAID);
        assertThat(slot.confidence()).isEqualTo(0.9);
    }

    @Test
    void userSaidWithoutHeardSpanIsDowngradedToInferred() {
        ConversationSession session = sessionWithHeard("I want to cancel my order please");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.9, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("INFERRED");
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.provenance()).isEqualTo(SlotProvenance.INFERRED);
        // Downgraded proposals are capped at 0.7, never 0.9.
        assertThat(slot.confidence()).isLessThanOrEqualTo(0.7);
    }

    @Test
    void confidenceIsClampedToUnitRange() {
        ConversationSession session = sessionWithHeard("order ORD-10001");

        DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 42.0, "USER_SAID"), QUEUER);
        assertThat(session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow().confidence())
                .isEqualTo(1.0);

        DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.TICKET_REFERENCE, "TCK-10001", -3.0, "USER_SAID"), QUEUER);
        assertThat(session.getDialogueState().getSlot(DialogueSlotNames.TICKET_REFERENCE).orElseThrow().confidence())
                .isEqualTo(0.0);
    }

    @Test
    void inferredConfidenceIsCappedAtPointSeven() {
        ConversationSession session = sessionWithHeard("something about an order");

        DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.95, "INFERRED"), QUEUER);

        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.provenance()).isEqualTo(SlotProvenance.INFERRED);
        assertThat(slot.confidence()).isEqualTo(0.7);
    }

    @Test
    void modelMayNotClaimToolReadProvenance() {
        ConversationSession session = sessionWithHeard("order ORD-10001");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 1.0, "TOOL_READ"), QUEUER);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("reserved for system lookups");
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.provenance()).isNotEqualTo(SlotProvenance.TOOL_READ);
    }

    @Test
    void existingToolReadValueAlwaysWinsOverProposals() {
        ConversationSession session = sessionWithHeard("my order is ORD-10001");
        session.getDialogueState().setSlot(
                new Slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 1.0, SlotProvenance.TOOL_READ, 1));

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.9, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("Kept the existing");
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.provenance()).isEqualTo(SlotProvenance.TOOL_READ);
        assertThat(slot.confidence()).isEqualTo(1.0);
    }

    @Test
    void mediumConfidenceSlotTriggersTargetedClarificationCopy() {
        ConversationSession session = sessionWithHeard("maybe order 10001");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.6, "USER_SAID"), QUEUER);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("Just to confirm");
        assertThat(result.message()).contains("0001");
    }

    @Test
    void lowConfidenceSlotIsUnusableAndGetsOpenClarification() {
        ConversationSession session = sessionWithHeard("something unclear");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.3, "INFERRED"), QUEUER);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("could you repeat the order number");
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.usableForMutation()).isFalse();
    }

    @Test
    void confirmSlotMakesItCertainAndResetsStrikes() {
        ConversationSession session = sessionWithHeard("something unclear");
        DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.3, "INFERRED"), QUEUER);
        assertThat(session.getDialogueState().slotStrikes(DialogueSlotNames.ORDER_REFERENCE)).isEqualTo(1);

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.CONFIRM_SLOT, DialogueSlotNames.ORDER_REFERENCE,
                        null, null, null, null, null, null), QUEUER);

        assertThat(result.ok()).isTrue();
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.confidence()).isEqualTo(1.0);
        assertThat(slot.provenance()).isEqualTo(SlotProvenance.USER_SAID);
        assertThat(slot.usableForMutation()).isTrue();
        assertThat(session.getDialogueState().slotStrikes(DialogueSlotNames.ORDER_REFERENCE)).isEqualTo(0);
    }

    @Test
    void correctionRecordsAntecedentAndUpdatesSlot() {
        ConversationSession session = sessionWithHeard("my order is ORD-10001", "no wait, ORD-10002");
        DialogueStateUpdater.apply(session,
                setSlot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.9, "USER_SAID"), QUEUER);

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.MARK_CORRECTION, DialogueSlotNames.ORDER_REFERENCE,
                        "ORD-10002", 0.9, "USER_SAID", null, null, null), QUEUER);

        assertThat(result.ok()).isTrue();
        Slot slot = session.getDialogueState().getSlot(DialogueSlotNames.ORDER_REFERENCE).orElseThrow();
        assertThat(slot.value()).isEqualTo("ORD-10002");
        assertThat(session.getDialogueState().getCorrectionAntecedents()).hasSize(1);
        CorrectionAntecedent antecedent = session.getDialogueState().getCorrectionAntecedents().get(0);
        assertThat(antecedent.slotName()).isEqualTo(DialogueSlotNames.ORDER_REFERENCE);
        assertThat(antecedent.oldValue()).isEqualTo("ORD-10001");
    }

    @Test
    void correctionOfUnknownSlotIsRejected() {
        ConversationSession session = sessionWithHeard("no, the other one");

        DialogueStateUpdateResult result = DialogueStateUpdater.apply(session,
                new DialogueStateProposal(DialogueStateAction.MARK_CORRECTION, DialogueSlotNames.ORDER_REFERENCE,
                        "ORD-10002", 0.9, "USER_SAID", null, null, null), QUEUER);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Nothing to correct");
    }

    @Test
    void obligationLifecycleCreatedAndDischarged() {
        ConversationSession session = sessionWithHeard("hello");
        DialogueState state = session.getDialogueState();

        state.addObligation(ObligationType.RESUME_DEFERRED, "cancellation on order ORD-10002", 1);
        assertThat(state.hasObligation(ObligationType.RESUME_DEFERRED)).isTrue();

        assertThat(state.dischargeObligation(ObligationType.RESUME_DEFERRED)).isTrue();
        assertThat(state.hasObligation(ObligationType.RESUME_DEFERRED)).isFalse();
        // Discharging again is a no-op, never an error.
        assertThat(state.dischargeObligation(ObligationType.RESUME_DEFERRED)).isFalse();
    }
}
