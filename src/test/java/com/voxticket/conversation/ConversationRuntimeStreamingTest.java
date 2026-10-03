package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.safety.HeuristicPromptGuard;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.PromptGuard;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * The streaming runtime preserves {@code processTurn} semantics while
 * delivering text to the sink: agent text arrives as native deltas during
 * the call, deterministic replies arrive as a single chunk, and the
 * recorded history always holds the fully assembled response.
 */
class ConversationRuntimeStreamingTest {

    private final IdentityService identityService = mock(IdentityService.class);
    private final SupportAgent supportAgent = mock(SupportAgent.class);
    private final InputNormalizer inputNormalizer = new InputNormalizer();
    private final PromptGuard promptGuard = new HeuristicPromptGuard(mock(TurnMetrics.class));
    private final ExplicitConfirmationParser confirmationParser = new ExplicitConfirmationParser();
    private final SensitiveTurnParser sensitiveTurnParser = new SensitiveTurnParser();
    private final ProcedureCoordinator procedureCoordinator = mock(ProcedureCoordinator.class);
    private final InMemorySessionStore sessionStore = new InMemorySessionStore();
    private final TurnMetrics turnMetrics = mock(TurnMetrics.class);
    private final ConversationAuditService auditService = mock(ConversationAuditService.class);
    private final ConversationRuntime runtime = new ConversationRuntime(
            sessionStore, identityService, supportAgent, inputNormalizer, promptGuard,
            confirmationParser, sensitiveTurnParser, procedureCoordinator, turnMetrics, auditService,
            new ConversationLanguageResolver(), new DirectProcedureResponseRenderer());

    private static Answer<AgentResponse> streamAnswer(String... deltas) {
        return inv -> {
            Consumer<String> sink = inv.getArgument(2);
            StringBuilder full = new StringBuilder();
            for (String delta : deltas) {
                sink.accept(delta);
                full.append(delta);
            }
            return new AgentResponse(full.toString(), AgentResponse.Outcome.SUCCESS);
        };
    }

    @BeforeEach
    void stubStreamingAgent() {
        when(supportAgent.streamResponse(any(), any(), any())).thenAnswer(streamAnswer("hello ", "world"));
        when(supportAgent.streamGuardedResponse(any(), any(), any())).thenAnswer(streamAnswer("guarded"));
    }

    @Test
    void normalTurnStreamsDeltasAndRecordsAssembledText() {
        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = runtime.streamTurn(
                new UserTurn("vs1", Channel.PHONE, "hello", null, Instant.now(), Map.of()), deltas::add);

        assertThat(deltas).containsExactly("hello ", "world");
        assertThat(turn.text()).isEqualTo("hello world");
        assertThat(turn.conversationState().channel()).isEqualTo(Channel.PHONE);
        assertThat(turn.conversationState().turnNumber()).isEqualTo(1);
    }

    @Test
    void deterministicRejectionArrivesAsSingleChunk() {
        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = runtime.streamTurn(
                new UserTurn("vs2", Channel.PHONE, "x".repeat(5000), null, Instant.now(), Map.of()), deltas::add);

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0)).isEqualTo(turn.text());
        assertThat(turn.text()).contains("too long");
    }

    @Test
    void secondTurnOnSameSessionKeepsStreaming() {
        List<String> first = new ArrayList<>();
        runtime.streamTurn(new UserTurn("vs3", Channel.PHONE, "hello", null, Instant.now(), Map.of()), first::add);
        List<String> second = new ArrayList<>();
        AssistantTurn turn = runtime.streamTurn(
                new UserTurn("vs3", Channel.PHONE, "again", null, Instant.now(), Map.of()), second::add);

        assertThat(first).containsExactly("hello ", "world");
        assertThat(second).containsExactly("hello ", "world");
        assertThat(turn.conversationState().turnNumber()).isEqualTo(2);
    }
}
