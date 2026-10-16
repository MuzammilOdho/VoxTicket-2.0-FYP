package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P2.4: confidence-policy thresholds, the mutation gate, and the 3-strikes
 * human-handoff rule.
 */
class ClarificationPolicyTest {

    private Slot slot(String name, String value, double confidence, SlotProvenance provenance) {
        return new Slot(name, value, confidence, provenance, 1);
    }

    @Test
    void highConfidenceActsDirectly() {
        Slot slot = slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.85, SlotProvenance.USER_SAID);

        assertThat(ClarificationPolicy.decide(slot)).isEqualTo(ClarificationPolicy.Decision.ACT_DIRECTLY);
        assertThat(ClarificationPolicy.usableForMutation(slot)).isTrue();
    }

    @Test
    void toolReadActsDirectlyRegardlessOfConfidence() {
        Slot slot = slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.1, SlotProvenance.TOOL_READ);

        assertThat(ClarificationPolicy.decide(slot)).isEqualTo(ClarificationPolicy.Decision.ACT_DIRECTLY);
        assertThat(ClarificationPolicy.usableForMutation(slot)).isTrue();
    }

    @Test
    void mediumConfidenceGetsTargetedClarification() {
        Slot slot = slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.6, SlotProvenance.USER_SAID);

        assertThat(ClarificationPolicy.decide(slot)).isEqualTo(ClarificationPolicy.Decision.TARGETED_CLARIFICATION);
        assertThat(ClarificationPolicy.usableForMutation(slot)).isTrue();
        String copy = ClarificationPolicy.renderTargetedClarification(slot);
        assertThat(copy).contains("Just to confirm");
        assertThat(copy).contains("0001");
    }

    @Test
    void lowConfidenceGetsOpenClarificationAndIsUnusable() {
        Slot slot = slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.3, SlotProvenance.INFERRED);

        assertThat(ClarificationPolicy.decide(slot)).isEqualTo(ClarificationPolicy.Decision.OPEN_CLARIFICATION);
        assertThat(ClarificationPolicy.usableForMutation(slot)).isFalse();
        assertThat(ClarificationPolicy.renderOpenClarification(slot)).contains("order number");
    }

    @Test
    void thresholdBoundaries() {
        assertThat(ClarificationPolicy.decide(slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.8, SlotProvenance.USER_SAID)))
                .isEqualTo(ClarificationPolicy.Decision.ACT_DIRECTLY);
        assertThat(ClarificationPolicy.decide(slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.5, SlotProvenance.USER_SAID)))
                .isEqualTo(ClarificationPolicy.Decision.TARGETED_CLARIFICATION);
        assertThat(ClarificationPolicy.decide(slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.49, SlotProvenance.USER_SAID)))
                .isEqualTo(ClarificationPolicy.Decision.OPEN_CLARIFICATION);
    }

    @Test
    void mutationGateBlocksShakySlotForMatchingReference() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.getDialogueState().setSlot(
                slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.3, SlotProvenance.INFERRED));

        assertThat(ClarificationPolicy.checkMutationBlocked(
                session.getDialogueState(), DialogueSlotNames.ORDER_REFERENCE, "ORD-10001"))
                .isPresent();
    }

    @Test
    void mutationGatePassesWhenReferenceDiffersFromShakySlot() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.getDialogueState().setSlot(
                slot(DialogueSlotNames.ORDER_REFERENCE, "ORD-10001", 0.3, SlotProvenance.INFERRED));

        // The model is acting on a different reference than the shaky slot -
        // the slot is not the basis for this mutation.
        assertThat(ClarificationPolicy.checkMutationBlocked(
                session.getDialogueState(), DialogueSlotNames.ORDER_REFERENCE, "ORD-10002"))
                .isEmpty();
    }

    @Test
    void mutationGatePassesWithNoSlot() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(ClarificationPolicy.checkMutationBlocked(
                session.getDialogueState(), DialogueSlotNames.ORDER_REFERENCE, "ORD-10001"))
                .isEmpty();
    }

    @Test
    void threeStrikesTriggerHandoffOffer() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        DialogueState state = session.getDialogueState();

        assertThat(state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.6)).isEqualTo(1);
        assertThat(state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.4)).isEqualTo(2);
        assertThat(state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.55)).isEqualTo(3);

        String offer = ClarificationPolicy.renderHandoffOffer(DialogueSlotNames.ORDER_REFERENCE);
        assertThat(offer).contains("human agent");
        assertThat(offer).contains("order number");
    }

    @Test
    void highConfidenceResetsStrikes() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        DialogueState state = session.getDialogueState();

        state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.6);
        state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.4);
        assertThat(state.recordSlotConfidence(DialogueSlotNames.ORDER_REFERENCE, 0.9)).isEqualTo(0);
        assertThat(state.slotStrikes(DialogueSlotNames.ORDER_REFERENCE)).isEqualTo(0);
    }

    @Test
    void updaterIncludesHandoffOfferOnThirdStrike() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("something unclear");
        DialogueStateUpdater.PendingRequestQueuer queuer = (s, t, o) -> "queued";

        DialogueStateUpdateResult third = null;
        for (int i = 0; i < 3; i++) {
            third = DialogueStateUpdater.apply(session,
                    new DialogueStateProposal(DialogueStateAction.SET_SLOT, DialogueSlotNames.ORDER_REFERENCE,
                            "ORD-10001", 0.3, "INFERRED", null, null, null),
                    queuer);
        }

        assertThat(third.ok()).isTrue();
        assertThat(third.message()).contains("human agent");
    }

    @Test
    void valueConfirmationNamesTheValue() {
        String copy = ClarificationPolicy.renderValueConfirmation("cancel", "ORD-10001");

        assertThat(copy).contains("ORD-10001");
        assertThat(copy).contains("is that correct?");
    }
}
