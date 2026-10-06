package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void firstProcedureBecomesActiveDirectly() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var procedure = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), IdentityAssurance.PHONE_MATCHED);

        session.startActiveProcedure(procedure);

        assertThat(session.getActiveProcedure()).contains(procedure);
        assertThat(session.getDeferredIntent()).isEmpty();
    }

    @Test
    void secondLiveProcedureIsRefusedWhileOneIsActive() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), IdentityAssurance.PHONE_MATCHED);
        var second = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.RETURN, dummyRef(), java.util.Map.of(), IdentityAssurance.OTP_VERIFIED);

        session.startActiveProcedure(first);

        assertThatThrownBy(() -> session.startActiveProcedure(second)).isInstanceOf(IllegalStateException.class);
        assertThat(session.getActiveProcedure()).contains(first);
        assertThat(session.getDeferredIntent()).isEmpty();
    }

    @Test
    void deferredIntentSlotHoldsAtMostOneAndNeverOverwrites() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), IdentityAssurance.PHONE_MATCHED);
        session.startActiveProcedure(first);

        var firstIntent = new com.voxticket.procedure.DeferredProcedureIntent(
                com.voxticket.procedure.ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                null, null, null, "1", null, Instant.now());
        var secondIntent = new com.voxticket.procedure.DeferredProcedureIntent(
                com.voxticket.procedure.ProcedureType.RETURN, "ORD-10003", UUID.randomUUID(),
                null, null, null, "1", null, Instant.now());

        session.setDeferredIntent(firstIntent);

        assertThatThrownBy(() -> session.setDeferredIntent(secondIntent)).isInstanceOf(IllegalStateException.class);
        assertThat(session.getDeferredIntent()).contains(firstIntent);
        assertThat(session.getActiveProcedure()).contains(first);
    }

    @Test
    void clearingActiveProcedureEmptiesTheSlotWithoutRestoringAnything() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        var first = new com.voxticket.procedure.ProcedureState(
                com.voxticket.procedure.ProcedureType.CLAIM, dummyRef(), java.util.Map.of(), IdentityAssurance.PHONE_MATCHED);
        session.startActiveProcedure(first);
        var intent = new com.voxticket.procedure.DeferredProcedureIntent(
                com.voxticket.procedure.ProcedureType.CANCELLATION, "ORD-10002", UUID.randomUUID(),
                null, null, null, "1", null, Instant.now());
        session.setDeferredIntent(intent);

        session.clearActiveProcedure();

        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getDeferredIntent()).contains(intent);
    }

    private com.voxticket.identity.VerifiedOrderRef dummyRef() {
        return new com.voxticket.identity.VerifiedOrderRef(
                UUID.randomUUID(), "ORD-TEST", UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, Instant.now());
    }
}