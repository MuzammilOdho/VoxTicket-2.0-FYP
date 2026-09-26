package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.RecentAction;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.procedure.ProcedureType;
import com.voxticket.rag.RagService;
import com.voxticket.service.CustomerOrderQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupportAgentSystemPromptTest {

    private final SupportAgent agent = newAgent();

    private SupportAgent newAgent() {
        return new SupportAgent(
                mock(TierChatClientRegistry.class), mock(ContextBuilder.class), mock(ModelSelector.class),
                mock(CustomerOrderQueryService.class), mock(RagService.class),
                mock(ProcedureCoordinator.class), mock(TurnMetrics.class), mock(ConversationAuditService.class));
    }

    private VerifiedOrderRef dummyRef(String orderNumber) {
        return new VerifiedOrderRef(UUID.randomUUID(), orderNumber, UUID.randomUUID(), IdentityAssurance.OTP_VERIFIED, Instant.now());
    }

    @Test
    void withNoRecentActionsOrProcedureThePromptIsUnchanged() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(agent.buildSystemPrompt(session)).doesNotContain("Recent activity").doesNotContain("active request");
    }

    @Test
    void aCancellationAppearsInTheRecentActivitySummary() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10001", "CANCELLED", null, "ORD-10001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("Recent activity");
        assertThat(prompt).contains("Order ORD-10001 was cancelled.");
    }

    @Test
    void aRefundIsDescribedWithItsAmountAndReferenceButNoMutableStatus() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10001", "PENDING", BigDecimal.valueOf(5000), "RFN-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("RFN-00001").contains("5000");
        assertThat(prompt).doesNotContain("PENDING");
    }

    @Test
    void thePromptInstructsTheModelToUseToolsForCurrentStatusRatherThanTrustingHistory() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.CLAIM_FILED, "ORD-20002", "OPEN", null, "CLM-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("historical facts only").contains("NOT necessarily still");
    }

    @Test
    void anActiveProcedureAwaitingConfirmationIsDescribedWithoutLeakingRawInternalValues() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.CLAIM, dummyRef("ORD-10001"), Map.of("itemReference", "SKU-1", "reason", "DAMAGED"), IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription("file a claim for Running Shoes on order ORD-10001 (damaged)");
        session.beginProcedure(procedure);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("file a claim for Running Shoes on order ORD-10001 (damaged)");
        assertThat(prompt).contains("explicitly say yes or no");
        assertThat(prompt).contains("Do not abandon or forget this");
        assertThat(prompt).doesNotContain("SKU-1");
        assertThat(prompt).doesNotContain("DAMAGED");
    }

    @Test
    void anActiveProcedureAwaitingVerificationIsDescribedCorrectly() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("cancel order ORD-10001");
        procedure.setStatus(com.voxticket.procedure.ProcedureStatus.AWAITING_VERIFICATION);
        session.beginProcedure(procedure);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("waiting for the customer to provide a verification code");
    }

    @Test
    void aPausedProcedureIsMentionedAlongsideTheActiveOne() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState first = new ProcedureState(ProcedureType.CLAIM, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.PHONE_MATCHED);
        first.setPendingDescription("file a claim for Running Shoes on order ORD-10001 (damaged)");
        ProcedureState second = new ProcedureState(ProcedureType.RETURN, dummyRef("ORD-20002"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        second.setPendingDescription("start a return for Cotton T-Shirt from order ORD-20002");
        session.beginProcedure(first);
        session.beginProcedure(second);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("start a return for Cotton T-Shirt from order ORD-20002");
        assertThat(prompt).contains("file a claim for Running Shoes on order ORD-10001 (damaged)");
        assertThat(prompt).contains("paused");
    }

    @Test
    void multipleRecentActionsAreAllIncluded() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10001", "CANCELLED", null, "ORD-10001", Instant.now()));
        session.recordAction(new RecentAction(RecentActionType.CLAIM_FILED, "ORD-20002", "OPEN", null, "CLM-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("ORD-10001 was cancelled").contains("CLM-00001");
    }

    @Test
    void hypotheticalQuestionsAreDistinguishedFromRealIncidentsInThePromptGuidance() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).contains("hypothetical").contains("do NOT call requestCancellation, requestReturn, or reportOrderProblem");
    }

    @Test
    void focusOrderAndItemAreSurfacedForFollowUpReferenceResolution() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordFocusOrder("ORD-10001");
        session.recordFocusItem("SKU-1", "Cotton Bedsheet Set");

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("ORD-10001").contains("Cotton Bedsheet Set");
        assertThat(prompt).contains("without repeating the order number");
        // The internal SKU is Java's own bookkeeping - it must never leak into the model's context.
        assertThat(prompt).doesNotContain("SKU-1");
    }

    @Test
    void blankResponseFallbackReferencesAPendingConfirmationRatherThanAGenericMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.RETURN, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("start a return for Cotton Bedsheet Set from order ORD-10001");
        session.beginProcedure(procedure);

        String fallback = agent.blankResponseFallback(session);

        assertThat(fallback).contains("start a return for Cotton Bedsheet Set from order ORD-10001");
    }

    @Test
    void blankResponseFallbackReferencesAPendingVerificationRatherThanAGenericMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("cancel order ORD-10001");
        procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
        session.beginProcedure(procedure);

        String fallback = agent.blankResponseFallback(session);

        assertThat(fallback).contains("verification code");
    }

    @Test
    void blankResponseFallbackIsGenericWithNoActiveProcedure() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(agent.blankResponseFallback(session)).isEqualTo("Sorry, could you say that again?");
    }

    @Test
    void aVoidAuthorizationCancellationExplainsWhyNoRefundIsNeededAsAStableFact() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10003",
                com.voxticket.policy.PaymentConsequence.VOID_AUTHORIZATION.name(), null, "ORD-10003", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("no refund is needed").contains("authorization was simply voided");
    }

    @Test
    void promptInstructsCallingTheProcedureToolEarlyRatherThanGatheringDetailsInFreeTextFirst() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).contains("call the matching tool right away");
    }

    @Test
    void promptForbidsInventingProcessDetailsWhenPolicySearchIsSilent() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).contains("do not invent specific mechanisms, timeframes, or promises");
        assertThat(prompt).contains("awaiting approval");
    }

    @Test
    void keyGuardrailPhrasesAreContiguousAcrossSourceLineBoundaries() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        // Regression test for the missing-space defect: these phrases must survive
        // Java text-block line boundaries as single contiguous strings.
        assertThat(prompt).contains("do not invent specific mechanisms, timeframes, or promises");
        assertThat(prompt).contains("call the matching tool right away");
        assertThat(prompt).doesNotContain("specific\nmechanisms");
        assertThat(prompt).doesNotContain("the\nmatching");
    }
}
