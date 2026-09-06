package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationSessionTest {

    @Test
    void turnNumberIncrementsWithEachUserMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        int first = session.recordUserMessage("hello");
        int second = session.recordUserMessage("how are you");

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(session.getTurnCount()).isEqualTo(2);
    }

    @Test
    void assistantMessagesAreTaggedWithTheCurrentTurnNumber() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("hello");
        session.recordAssistantMessage("hi there");

        assertThat(session.getRecentMessages()).hasSize(2);
        assertThat(session.getRecentMessages().get(1).turnNumber()).isEqualTo(1);
        assertThat(session.getRecentMessages().get(1).role()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    void recentActionsAreCappedAtTenAndEvictOldestFirst() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        for (int i = 0; i < 12; i++) {
            session.recordAction(new RecentAction(
                    RecentActionType.REFUND_INITIATED, "ORD-" + i, "PENDING", BigDecimal.TEN, "RFN-" + i, Instant.now()));
        }

        assertThat(session.getRecentActions()).hasSize(10);
        assertThat(session.getRecentActions().get(0).target()).isEqualTo("ORD-2");
        assertThat(session.getRecentActions().get(9).target()).isEqualTo("ORD-11");
    }

    @Test
    void firstIdentityResolutionIsAlwaysAccepted() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        CustomerIdentity resolved = new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567");

        session.applyResolvedIdentity(resolved);

        assertThat(session.getCustomerIdentity()).isEqualTo(resolved);
    }

    @Test
    void onceAnchoredToACustomerALaterResolutionForADifferentCustomerIsIgnored() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        CustomerIdentity customerA = new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567");
        CustomerIdentity customerB = new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923009999999");

        session.applyResolvedIdentity(customerA);
        session.applyResolvedIdentity(customerB);

        assertThat(session.getCustomerIdentity()).isEqualTo(customerA);
    }

    @Test
    void higherAssuranceForTheSameCustomerIsAccepted() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        UUID customerId = UUID.randomUUID();
        CustomerIdentity phoneMatched = new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567");
        CustomerIdentity otpVerified = new CustomerIdentity(customerId, IdentityAssurance.OTP_VERIFIED, "+923001234567");

        session.applyResolvedIdentity(phoneMatched);
        session.applyResolvedIdentity(otpVerified);

        assertThat(session.getCustomerIdentity().assuranceLevel()).isEqualTo(IdentityAssurance.OTP_VERIFIED);
    }

    @Test
    void anAlreadyAnchoredSessionIsNeverDowngradedByALaterAnonymousResolution() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        UUID customerId = UUID.randomUUID();
        CustomerIdentity phoneMatched = new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567");

        session.applyResolvedIdentity(phoneMatched);
        session.applyResolvedIdentity(CustomerIdentity.anonymous());

        assertThat(session.getCustomerIdentity()).isEqualTo(phoneMatched);
    }

    @org.junit.jupiter.api.Test
    void firstProcedureBecomesActiveDirectly() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var procedure = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.PHONE_MATCHED);

        var result = session.beginProcedure(procedure);

        assertThat(result).isEqualTo(com.voxticket.procedure.ProcedureSlotResult.STARTED);
        assertThat(session.getActiveProcedure()).contains(procedure);
        assertThat(session.getPausedProcedure()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void secondProcedurePausesTheFirst() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.PHONE_MATCHED);
        var second = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.RETURN, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.OTP_VERIFIED);

        session.beginProcedure(first);
        var result = session.beginProcedure(second);

        assertThat(result).isEqualTo(com.voxticket.procedure.ProcedureSlotResult.STARTED_AND_PAUSED_PREVIOUS);
        assertThat(session.getActiveProcedure()).contains(second);
        assertThat(session.getPausedProcedure()).contains(first);
    }

    @org.junit.jupiter.api.Test
    void thirdProcedureIsRejectedWithBothSlotsUntouched() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.PHONE_MATCHED);
        var second = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.RETURN, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.OTP_VERIFIED);
        var third = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CANCELLATION, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.OTP_VERIFIED);

        session.beginProcedure(first);
        session.beginProcedure(second);
        var result = session.beginProcedure(third);

        assertThat(result).isEqualTo(com.voxticket.procedure.ProcedureSlotResult.BOTH_SLOTS_OCCUPIED);
        assertThat(session.getActiveProcedure()).contains(second);
        assertThat(session.getPausedProcedure()).contains(first);
    }

    @org.junit.jupiter.api.Test
    void clearingActiveProcedureResumesThePausedOne() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.PHONE_MATCHED);
        var second = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.RETURN, dummyRef(), java.util.Map.of(), com.voxticket.identity.IdentityAssurance.OTP_VERIFIED);
        session.beginProcedure(first);
        session.beginProcedure(second);

        session.clearActiveProcedure();

        assertThat(session.getActiveProcedure()).contains(first);
        assertThat(session.getPausedProcedure()).isEmpty();
    }

    private com.voxticket.identity.VerifiedOrderRef dummyRef() {
        return new com.voxticket.identity.VerifiedOrderRef(
                UUID.randomUUID(), "ORD-TEST", UUID.randomUUID(), com.voxticket.identity.IdentityAssurance.PHONE_MATCHED, Instant.now());
    }
}