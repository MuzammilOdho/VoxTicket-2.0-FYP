package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.RecentAction;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.rag.PolicyKnowledgeTools;
import com.voxticket.service.CustomerOrderQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** Tests only the pure string-building logic - never touches the ChatClient fluent chain. */
class SupportAgentSystemPromptTest {

    private final SupportAgent agent = newAgent();

    private SupportAgent newAgent() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new SupportAgent(
                builder, mock(ContextBuilder.class), mock(ModelSelector.class),
                mock(CustomerOrderQueryService.class), mock(PolicyKnowledgeTools.class),
                mock(ProcedureCoordinator.class), 0.3);
    }

    @Test
    void withNoRecentActionsThePromptIsUnchanged() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(agent.buildSystemPrompt(session)).doesNotContain("Recent activity");
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
    void aRefundWithAnAmountIsDescribedWithItsAmountAndReference() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.REFUND_INITIATED, "ORD-10001", "PENDING", BigDecimal.valueOf(5000), "RFN-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("RFN-00001");
        assertThat(prompt).contains("5000");
    }

    @Test
    void multipleRecentActionsAreAllIncluded() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, "ORD-10001", "CANCELLED", null, "ORD-10001", Instant.now()));
        session.recordAction(new RecentAction(RecentActionType.CLAIM_FILED, "ORD-20002", "OPEN", null, "CLM-00001", Instant.now()));

        String prompt = agent.buildSystemPrompt(session);

        assertThat(prompt).contains("ORD-10001 was cancelled");
        assertThat(prompt).contains("CLM-00001");
    }
}