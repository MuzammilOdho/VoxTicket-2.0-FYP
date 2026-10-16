package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.PromptVersion;
import com.voxticket.agent.SupportAgent;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.safety.HeuristicPromptGuard;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3.2: the first turn of a PHONE-channel session gets the deterministic
 * recording disclosure prepended to the assistant's greeting - in the
 * returned text, the recorded history, and the first emitted stream delta.
 * Later turns and CHAT sessions are untouched.
 */
class RecordingDisclosureTest {

    private static final String DISCLOSURE = "This call may be recorded for quality and training. ";

    private final IdentityService identityService = mock(IdentityService.class);
    private final SupportAgent supportAgent = mock(SupportAgent.class);
    private final InMemorySessionStore sessionStore = new InMemorySessionStore();
    private final ConversationAuditService auditService = mock(ConversationAuditService.class);

    private final ConversationRuntime runtime = new ConversationRuntime(
            sessionStore, identityService, supportAgent, new InputNormalizer(),
            new HeuristicPromptGuard(mock(TurnMetrics.class)),
            new ExplicitConfirmationParser(), new SensitiveTurnParser(),
            mock(ProcedureCoordinator.class), mock(TurnMetrics.class), auditService,
            new ConversationLanguageResolver(), new DirectProcedureResponseRenderer());

    @BeforeEach
    void stubAgent() {
        when(supportAgent.respond(any(), any()))
                .thenReturn(new AgentResponse("Hello! How can I help?", AgentResponse.Outcome.SUCCESS));
        // Streaming path: emit one delta through the provided sink, like the
        // real agent's first token batch.
        when(supportAgent.respondStream(any(), any(), any(), any())).thenAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(3);
            sink.accept("Hello! How can I help?");
            return new AgentResponse("Hello! How can I help?", AgentResponse.Outcome.SUCCESS);
        });
        when(supportAgent.currentPromptVersion())
                .thenReturn(new PromptVersion("v1", "system", "test-content-hash"));
        when(identityService.resolveByPhone("+923001234567"))
                .thenReturn(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567"));
    }

    private UserTurn phoneTurn(String sessionId, String text) {
        return new UserTurn(sessionId, Channel.PHONE, text, "+923001234567", Instant.now(), Map.of());
    }

    @Test
    void firstPhoneTurnPrependsDisclosureToGreeting() {
        AssistantTurn response = runtime.processTurn(phoneTurn("disclosure-1", "hello"));

        assertThat(response.text()).startsWith(DISCLOSURE);
    }

    @Test
    void secondPhoneTurnHasNoDisclosure() {
        runtime.processTurn(phoneTurn("disclosure-2", "hello"));
        AssistantTurn second = runtime.processTurn(phoneTurn("disclosure-2", "still there?"));

        assertThat(second.text()).doesNotContain("recorded for quality");
    }

    @Test
    void chatTurnHasNoDisclosure() {
        AssistantTurn response = runtime.processTurn(
                new UserTurn("disclosure-3", Channel.CHAT, "hello", null, Instant.now(), Map.of()));

        assertThat(response.text()).doesNotContain("recorded for quality");
    }

    @Test
    void disclosureIsPrependedToFirstStreamedDelta() {
        List<String> deltas = new ArrayList<>();
        runtime.processTurnStream(phoneTurn("disclosure-4", "hello"), deltas::add);

        assertThat(deltas).isNotEmpty();
        assertThat(deltas.get(0)).startsWith(DISCLOSURE);
    }

    @Test
    void disclosureIsInRecordedHistory() {
        runtime.processTurn(phoneTurn("disclosure-5", "hello"));

        List<ConversationMessage> messages = sessionStore
                .withSession("disclosure-5", Channel.PHONE, session -> session.getRecentMessages());
        String assistantText = messages.stream()
                .filter(m -> m.role() == MessageRole.ASSISTANT)
                .map(ConversationMessage::text)
                .findFirst()
                .orElseThrow();
        assertThat(assistantText).startsWith(DISCLOSURE);
    }
}
