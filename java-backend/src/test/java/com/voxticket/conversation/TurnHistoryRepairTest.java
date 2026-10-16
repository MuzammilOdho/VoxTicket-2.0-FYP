package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.conversation.DirectProcedureResponseRenderer;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.safety.HeuristicPromptGuard;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * P1.3 (history repair): an aborted turn writes back exactly what the
 * caller heard - the concatenated emitted deltas, flagged partial - instead
 * of dropping the partial reply entirely.
 */
class TurnHistoryRepairTest {

    private final InMemorySessionStore sessionStore = new InMemorySessionStore();
    private final SupportAgent supportAgent = mock(SupportAgent.class);
    private final ConversationAuditService auditService = mock(ConversationAuditService.class);
    private final ConversationRuntime runtime = new ConversationRuntime(
            sessionStore, mock(IdentityService.class), supportAgent, new InputNormalizer(),
            new HeuristicPromptGuard(mock(TurnMetrics.class)),
            new ExplicitConfirmationParser(), new SensitiveTurnParser(),
            mock(ProcedureCoordinator.class), mock(TurnMetrics.class), auditService,
            new ConversationLanguageResolver(), new DirectProcedureResponseRenderer());

    @Test
    void abortAfterNDeltasWritesPartialAssistantMessageWithExactlyThoseDeltas() {
        Answer<AgentResponse> abortMidStream = invocation -> {
            Consumer<String> sink = invocation.getArgument(3);
            sink.accept("Your order ");
            sink.accept("is on its way.");
            throw new TurnAbortedException(new IllegalStateException("client disconnected"));
        };
        when(supportAgent.respondStream(any(), any(), any(), any())).thenAnswer(abortMidStream);

        UserTurn turn = new UserTurn("repair-1", Channel.CHAT, "where is my order", null, Instant.now(), Map.of());
        assertThatThrownBy(() -> runtime.processTurnStream(turn, delta -> {
        })).isInstanceOf(TurnAbortedException.class);

        List<ConversationMessage> messages = sessionStore.withSession("repair-1", Channel.CHAT,
                ConversationSession::getRecentMessages);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(1).role()).isEqualTo(MessageRole.ASSISTANT);
        // Exactly the emitted deltas - nothing generated-but-unheard.
        assertThat(messages.get(1).text()).isEqualTo("Your order is on its way.");
        assertThat(messages.get(1).partial()).isTrue();
    }

    @Test
    void abortWithZeroDeltasWritesNoAssistantMessage() {
        when(supportAgent.respondStream(any(), any(), any(), any()))
                .thenThrow(new TurnAbortedException(new IllegalStateException("client disconnected")));

        UserTurn turn = new UserTurn("repair-2", Channel.CHAT, "hello", null, Instant.now(), Map.of());
        assertThatThrownBy(() -> runtime.processTurnStream(turn, delta -> {
        })).isInstanceOf(TurnAbortedException.class);

        List<ConversationMessage> messages = sessionStore.withSession("repair-2", Channel.CHAT,
                ConversationSession::getRecentMessages);
        // Today's behavior is kept: user message stands alone.
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo(MessageRole.USER);
    }

    @Test
    void abortedTurnReleasesOwnershipForTheNextTurn() {
        when(supportAgent.respondStream(any(), any(), any(), any()))
                .thenThrow(new TurnAbortedException(new IllegalStateException("client disconnected")));
        when(supportAgent.respond(any(), any()))
                .thenReturn(new AgentResponse("recovered", AgentResponse.Outcome.SUCCESS));

        UserTurn aborted = new UserTurn("repair-3", Channel.CHAT, "hello", null, Instant.now(), Map.of());
        assertThatThrownBy(() -> runtime.processTurnStream(aborted, delta -> {
        })).isInstanceOf(TurnAbortedException.class);

        // The session is usable again: the abort released ownership and the
        // repaired history (user message only) is intact.
        UserTurn next = new UserTurn("repair-3", Channel.CHAT, "still there?", null, Instant.now(), Map.of());
        AssistantTurn response = runtime.processTurn(next);
        assertThat(response.text()).isEqualTo("recovered");

        List<ConversationMessage> messages = sessionStore.withSession("repair-3", Channel.CHAT,
                ConversationSession::getRecentMessages);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(2).partial()).isFalse();
    }
}
