package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.IdentityService;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureOutcome;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.procedure.ProcedureType;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 1B: while a verification challenge is pending, sensitive parsing and
 * redaction must happen BEFORE the prompt guard runs, because an ML-backed
 * guard is a remote call. The guard must never receive OTP plaintext; the
 * raw code may only reach the local verification logic.
 */
class PromptGuardOtpPrivacyTest {

    private static final String OTP = "482916";

    /** PromptGuard double that records every input it is asked to evaluate. */
    private static final class CapturingGuard implements PromptGuard {
        final List<String> seen = new ArrayList<>();
        private final PromptGuardVerdict verdict;

        CapturingGuard(PromptGuardVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public PromptGuardVerdict evaluate(String userInput) {
            seen.add(userInput);
            return verdict;
        }
    }

    private final IdentityService identityService = mock(IdentityService.class);
    private final SupportAgent supportAgent = mock(SupportAgent.class);
    private final ProcedureCoordinator procedureCoordinator = mock(ProcedureCoordinator.class);
    private final TurnMetrics turnMetrics = mock(TurnMetrics.class);
    private final ConversationAuditService auditService = mock(ConversationAuditService.class);
    private final InMemorySessionStore sessionStore = new InMemorySessionStore();

    private CapturingGuard guard;
    private ConversationRuntime runtime;

    @BeforeEach
    void setUp() {
        guard = new CapturingGuard(PromptGuardVerdict.allow());
        runtime = new ConversationRuntime(
                sessionStore, identityService, supportAgent, new InputNormalizer(), guard,
                new ExplicitConfirmationParser(), new SensitiveTurnParser(), procedureCoordinator,
                turnMetrics, auditService,
                new ConversationLanguageResolver(), new DirectProcedureResponseRenderer());
        when(supportAgent.respond(any(), any()))
                .thenReturn(new AgentResponse("stubbed agent response", AgentResponse.Outcome.SUCCESS));
        when(supportAgent.respondGuarded(any(), any()))
                .thenReturn(new AgentResponse("stubbed guarded agent response", AgentResponse.Outcome.SUCCESS));
        when(identityService.resolveByPhone(any()))
                .thenReturn(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.ANONYMOUS, null));
    }

    private void startAwaitingVerification(String sessionId) {
        sessionStore.withSession(sessionId, Channel.CHAT, session -> {
            VerifiedOrderRef ref = new VerifiedOrderRef(UUID.randomUUID(), "ORD-10001",
                    UUID.randomUUID(), IdentityAssurance.OTP_VERIFIED, Instant.now());
            ProcedureState procedure = new ProcedureState(ProcedureType.CANCELLATION, ref, Map.of(),
                    IdentityAssurance.OTP_VERIFIED);
            procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
            session.startActiveProcedure(procedure);
            return null;
        });
    }

    @Test
    void promptGuardNeverReceivesOtpPlaintextWhileAwaitingVerification() {
        startAwaitingVerification("otp-privacy-1");
        when(procedureCoordinator.submitVerificationCode(any(), eq(OTP)))
                .thenReturn(ProcedureOutcome.ok("CANCELLED", "ok", Map.of("orderReference", "ORD-10001")));

        AssistantTurn response = runtime.processTurn(
                new UserTurn("otp-privacy-1", Channel.CHAT, OTP, null, Instant.now(), Map.of()));

        // The guard ran (defense-in-depth is intact) but only ever saw the
        // redacted text.
        assertThat(guard.seen).isNotEmpty();
        assertThat(guard.seen).allSatisfy(input -> assertThat(input).doesNotContain(OTP));
        assertThat(guard.seen).anySatisfy(input -> assertThat(input).contains("[verification code provided]"));

        // The OTP plaintext still reached the local verification logic.
        verify(procedureCoordinator).submitVerificationCode(any(), eq(OTP));
        assertThat(response.metadata()).containsEntry("orderReference", "ORD-10001");
    }

    @Test
    void guardBlockWhileAwaitingVerificationStillSeesOnlyRedactedText() {
        guard = new CapturingGuard(PromptGuardVerdict.flagged("test-category", "test-pattern"));
        runtime = new ConversationRuntime(
                sessionStore, identityService, supportAgent, new InputNormalizer(), guard,
                new ExplicitConfirmationParser(), new SensitiveTurnParser(), procedureCoordinator,
                turnMetrics, auditService,
                new ConversationLanguageResolver(), new DirectProcedureResponseRenderer());
        startAwaitingVerification("otp-privacy-2");

        AssistantTurn response = runtime.processTurn(
                new UserTurn("otp-privacy-2", Channel.CHAT, OTP, null, Instant.now(), Map.of()));

        // Blocked as before, but the flagged input handed to the guard
        // contained no OTP plaintext.
        assertThat(response.text()).contains("I'm not able to help with that");
        assertThat(guard.seen).isNotEmpty();
        assertThat(guard.seen).allSatisfy(input -> assertThat(input).doesNotContain(OTP));
    }

    @Test
    void nonVerificationTurnsStillSendRawTextToGuard() {
        AssistantTurn response = runtime.processTurn(
                new UserTurn("otp-privacy-3", Channel.CHAT, "where is my order", null, Instant.now(), Map.of()));

        assertThat(guard.seen).containsExactly("where is my order");
        assertThat(response.text()).isEqualTo("stubbed agent response");
    }
}
