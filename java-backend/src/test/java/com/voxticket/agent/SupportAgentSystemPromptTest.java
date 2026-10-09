package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.ConversationLanguageResolver;
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
                mock(ProcedureCoordinator.class), mock(TurnMetrics.class), mock(ConversationAuditService.class),
                new ConversationLanguageResolver());
    }

    private VerifiedOrderRef dummyRef(String orderNumber) {
        return new VerifiedOrderRef(UUID.randomUUID(), orderNumber, UUID.randomUUID(), IdentityAssurance.OTP_VERIFIED, Instant.now());
    }

    @Test
    void basePromptIsACompactGlobalContract() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).contains("You are VoxTicket, an e-commerce customer-support assistant.");
        assertThat(prompt).contains("COMMUNICATION").contains("GROUNDING").contains("ACTIONS")
                .contains("CONVERSATION").contains("SAFETY");
        assertThat(prompt).contains("Never invent an order, status, amount, date, cause, policy,");
        assertThat(prompt).contains("Never say an action succeeded unless the procedure result says it");
        assertThat(prompt).contains("Handle every requested part of a multi-intent message.");
        assertThat(prompt).contains("The application controls customer identity and ownership.");
    }

    @Test
    void basePromptKeepsNoneOfTheLegacyLongGuidance() {
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).doesNotContain("VOICE-FIRST");
        assertThat(prompt).doesNotContain("do NOT call requestCancellation");
        assertThat(prompt).doesNotContain("reportOrderProblem");
        assertThat(prompt).doesNotContain("call the matching tool right away");
        assertThat(prompt).doesNotContain("do not invent specific mechanisms, timeframes, or promises");
    }

    @Test
    void withNoSessionStateThePromptIsJustTheBaseContract() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(agent.buildSystemPrompt(session)).doesNotContain("CONVERSATION STATE");
    }

    @Test
    void conversationStateBlockSurfacesFocusProcedureAndEffectsCompactly() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordFocusOrder("ORD-10004");
        session.recordFocusItem("SKU-9", "Mechanical Keyboard");
        ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("cancel order ORD-10001");
        procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
        session.startActiveProcedure(procedure);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10003", "REFUND_REQUIRED", null, "ORD-10003", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("CONVERSATION STATE");
        assertThat(prompt).contains("focusOrder: ORD-10004");
        assertThat(prompt).contains("focusItem: Mechanical Keyboard");
        assertThat(prompt).contains("activeProcedure: CANCELLATION");
        assertThat(prompt).contains("activeProcedureStatus: AWAITING_VERIFICATION");
        assertThat(prompt).contains("activeTarget: cancel order ORD-10001");
        assertThat(prompt).contains("recentEffects:");
        assertThat(prompt).contains("- ORDER_CANCELLED ORD-10003 REFUND_REQUIRED");
    }

    @Test
    void stateBlockOmitsFieldsThatDoNotExist() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordFocusOrder("ORD-10001");

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("CONVERSATION STATE").contains("focusOrder: ORD-10001");
        assertThat(prompt).doesNotContain("focusItem:");
        assertThat(prompt).doesNotContain("activeProcedure:");
        assertThat(prompt).doesNotContain("pausedProcedure:");
        assertThat(prompt).doesNotContain("recentEffects:");
    }

    @Test
    void noInternalSecretsLeakIntoTheStateBlock() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordFocusOrder("ORD-10001");
        session.recordFocusItem("SKU-1", "Cotton Bedsheet Set");
        ProcedureState procedure = new ProcedureState(ProcedureType.CLAIM, dummyRef("ORD-10001"),
                Map.of("itemReference", "SKU-1", "reason", "DAMAGED", "description", "torn seam"), IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription("file a claim for Cotton Bedsheet Set on order ORD-10001 (damaged)");
        session.startActiveProcedure(procedure);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("focusItem: Cotton Bedsheet Set");
        assertThat(prompt).contains("activeTarget: file a claim for Cotton Bedsheet Set on order ORD-10001 (damaged)");
        assertThat(prompt).doesNotContain("SKU-1");
        assertThat(prompt).doesNotContain("DAMAGED");
        assertThat(prompt).doesNotContain("torn seam");
    }

    @Test
    void anActiveProcedureAwaitingConfirmationUsesTheStableStatusCode() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.CLAIM, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription("file a claim for Running Shoes on order ORD-10001 (damaged)");
        session.startActiveProcedure(procedure);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("activeProcedure: CLAIM");
        assertThat(prompt).contains("activeProcedureStatus: AWAITING_CONFIRMATION");
        assertThat(prompt).doesNotContain("explicitly say yes or no");
        assertThat(prompt).doesNotContain("Do not abandon or forget this");
    }

    @Test
    void aDeferredIntentIsRepresentedCompactlyAlongsideTheActiveProcedure() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState active = new ProcedureState(ProcedureType.RETURN, dummyRef("ORD-20002"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        active.setPendingDescription("start a return for Cotton T-Shirt from order ORD-20002");
        session.startActiveProcedure(active);
        session.setDeferredIntent(new com.voxticket.procedure.DeferredProcedureIntent(
                ProcedureType.CLAIM, "ORD-10001", UUID.randomUUID(), "Running Shoes", "SKU-RS-9", "DAMAGED", "1", null, Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("activeProcedure: RETURN");
        assertThat(prompt).contains("activeTarget: start a return for Cotton T-Shirt from order ORD-20002");
        assertThat(prompt).contains("deferredProcedure: CLAIM");
        assertThat(prompt).contains("deferredTarget: ORD-10001");
        assertThat(prompt).contains("deferredItem: Running Shoes");
        // The retained scoped item identity (SKU) is internal-only: the
        // model sees the display name, never the SKU.
        assertThat(prompt).doesNotContain("SKU-RS-9");
        assertThat(prompt).doesNotContain("pausedProcedure:");
    }

    @Test
    void recentEffectsAreCappedAtTheLatestThree() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10001", "PENDING", BigDecimal.ONE, "RFN-00001", Instant.now()));
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10002", "PENDING", BigDecimal.ONE, "RFN-00002", Instant.now()));
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10003", "PENDING", BigDecimal.ONE, "RFN-00003", Instant.now()));
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10004", "PENDING", BigDecimal.ONE, "RFN-00004", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).doesNotContain("RFN-00001");
        assertThat(prompt).contains("- REFUND_INITIATED ORD-10002 RFN-00002");
        assertThat(prompt).contains("- REFUND_INITIATED ORD-10003 RFN-00003");
        assertThat(prompt).contains("- REFUND_INITIATED ORD-10004 RFN-00004");
    }

    @Test
    void aRefundEffectCarriesItsReferenceButNoMutableStatus() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10001", "PENDING", BigDecimal.valueOf(5000), "RFN-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("- REFUND_INITIATED ORD-10001 RFN-00001");
        assertThat(prompt).doesNotContain("PENDING");
    }

    @Test
    void aCancelledOrderEffectPreservesThePaymentConsequenceAsAStableCode() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10003",
                com.voxticket.policy.PaymentConsequence.VOID_AUTHORIZATION.name(), null, "ORD-10003", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("- ORDER_CANCELLED ORD-10003 VOID_AUTHORIZATION");
        assertThat(prompt).doesNotContain("authorization was simply voided");
    }

    @Test
    void effectsAreMarkedAsHistoricalFactsNotCurrentState() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.CLAIM_FILED, "ORD-20002", "OPEN", null, "CLM-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("- CLAIM_FILED ORD-20002 CLM-00001");
        assertThat(prompt).contains("historical facts only");
        assertThat(prompt).contains("getMyOrderContext").contains("getMyTicketStatus");
    }

    @Test
    void blankResponseFallbackReferencesAPendingConfirmationRatherThanAGenericMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.RETURN, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("start a return for Cotton Bedsheet Set from order ORD-10001");
        session.startActiveProcedure(procedure);

        String fallback = agent.blankResponseFallback(session);

        assertThat(fallback).contains("start a return for Cotton Bedsheet Set from order ORD-10001");
    }

    @Test
    void blankResponseFallbackReferencesAPendingVerificationRatherThanAGenericMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("cancel order ORD-10001");
        procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
        session.startActiveProcedure(procedure);

        String fallback = agent.blankResponseFallback(session);

        assertThat(fallback).contains("verification code");
    }

    @Test
    void blankResponseFallbackIsGenericWithNoActiveProcedure() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(agent.blankResponseFallback(session)).isEqualTo("Sorry, could you say that again?");
    }

    // ---- Phase 5: multilingual communication profile ----

    @Test
    void systemPromptInjectsTheResolvedCustomerLanguageProfile() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);
        session.recordUserMessage("میرا آرڈر کہاں ہے");

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("COMMUNICATION PROFILE");
        assertThat(prompt).contains("customerLanguage: URDU");
        assertThat(prompt).contains("Urdu script");
    }

    @Test
    void systemPromptInjectsRomanUrduProfileForRomanUrduSpeech() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);
        session.recordUserMessage("mera order kahan hai");

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("customerLanguage: ROMAN_URDU");
    }

    @Test
    void systemPromptInjectsCodeSwitchProfileForMixedSpeech() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);
        session.recordUserMessage("mera order kahan hai, please cancel kar dein");

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("customerLanguage: CODE_SWITCH");
    }

    @Test
    void systemPromptDefaultsToEnglishProfileWithNoLanguageSignal() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("customerLanguage: ENGLISH");
    }

    @Test
    void staticCommunicationSectionDefersToTheInjectedProfile() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("follow them exactly");
        assertThat(prompt).doesNotContain("or code-switching");
    }

    @Test
    void blankResponseFallbackRendersInTheSessionLanguage() {
        ConversationSession urdu = ConversationSession.newSession("s5u", Channel.CHAT);
        urdu.recordUserMessage("میرا آرڈر کہاں ہے");
        ConversationSession romanUrdu = ConversationSession.newSession("s5r", Channel.CHAT);
        romanUrdu.recordUserMessage("mera order kahan hai");
        ConversationSession codeSwitch = ConversationSession.newSession("s5c", Channel.CHAT);
        codeSwitch.recordUserMessage("mera order kahan hai, please cancel kar dein");

        assertThat(agent.blankResponseFallback(urdu)).isEqualTo("معذرت، کیا آپ دوبارہ کہہ سکتے ہیں؟");
        assertThat(agent.blankResponseFallback(romanUrdu)).isEqualTo("Maaf kijiye, kya aap dobara keh sakte hain?");
        assertThat(agent.blankResponseFallback(codeSwitch)).isEqualTo("Sorry, kya aap dobara keh sakte hain?");
    }

    @Test
    void blankResponseFallbackForPendingVerificationRendersInTheSessionLanguage() {
        ConversationSession session = ConversationSession.newSession("s5", Channel.CHAT);
        session.recordUserMessage("میرا آرڈر کہاں ہے");
        ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, dummyRef("ORD-10001"), Map.of(), IdentityAssurance.OTP_VERIFIED);
        procedure.setPendingDescription("cancel order ORD-10001");
        procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
        session.startActiveProcedure(procedure);

        assertThat(agent.blankResponseFallback(session))
                .isEqualTo("معذرت، کیا آپ دوبارہ کہہ سکتے ہیں؟ میں اب بھی تصدیقی کوڈ کا انتظار کر رہا ہوں۔");
    }
}
