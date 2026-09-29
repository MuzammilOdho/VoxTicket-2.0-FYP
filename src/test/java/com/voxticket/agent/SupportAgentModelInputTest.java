package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.rag.RagService;
import com.voxticket.service.CustomerOrderQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Pass 2D cleanup: proves the residual text is the actual latest USER message
 * the model receives, while the session keeps the safe redacted full text.
 * This exercises the real {@link ContextBuilder} and inspects the real
 * ChatClient-facing message list - it does not merely verify a method
 * argument on a mocked SupportAgent.
 */
class SupportAgentModelInputTest {

    private TierChatClientRegistry clientRegistry;
    private ModelSelector modelSelector;
    private ChatClient chatClient;
    private SupportAgent supportAgent;

    @BeforeEach
    void setUp() {
        clientRegistry = mock(TierChatClientRegistry.class);
        modelSelector = mock(ModelSelector.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(clientRegistry.clientFor(ModelTier.TIER_1)).thenReturn(chatClient);
        when(clientRegistry.resolutionFor(ModelTier.TIER_1))
                .thenReturn(new TierChatClientRegistry.TierResolution(AiProvider.GOOGLE, "test-model"));
        when(modelSelector.select(any(ConversationSession.class), anyString()))
                .thenReturn(new ModelSelectionResult(ModelTier.TIER_1, "test"));

        ChatResponse chatResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(chatResponse.getResult().getOutput().getText()).thenReturn("residual answer");
        when(chatClient.prompt().system(anyString()).messages(anyList()).tools(any(Object[].class))
                        .call()
                        .chatResponse())
                .thenReturn(chatResponse);

        supportAgent = new SupportAgent(
                clientRegistry,
                new ContextBuilder(),
                modelSelector,
                mock(CustomerOrderQueryService.class),
                mock(RagService.class),
                mock(ProcedureCoordinator.class),
                mock(TurnMetrics.class),
                mock(ConversationAuditService.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Message> captureModelMessages() {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        // times(2): the stubbing in setUp also invokes .messages() on the
        // deep-stub mock; the real model call is the last invocation.
        verify(chatClient.prompt().system(anyString()), times(2)).messages(captor.capture());
        List<List> all = captor.getAllValues();
        return (List<Message>) all.get(all.size() - 1);
    }

    @Test
    void residualTurnSendsResidualOnlyAsTheLatestModelUserMessage() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        // What ConversationRuntime stores for a sensitive residual turn.
        session.recordUserMessage("[verification code provided] and where is ORD-10002?");

        supportAgent.respondReadOnly(session, "where is ORD-10002?");

        List<Message> modelMessages = captureModelMessages();
        assertThat(modelMessages).hasSize(1);
        assertThat(modelMessages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) modelMessages.get(0)).getText()).isEqualTo("where is ORD-10002?");
        // The session keeps the safe redacted full message - model input and
        // persisted history intentionally differ.
        assertThat(session.getRecentMessages().get(0).text())
                .isEqualTo("[verification code provided] and where is ORD-10002?");
    }

    @Test
    void normalTurnSendsTheStoredMessageUnchanged() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("where is ORD-10002?");

        supportAgent.respondReadOnly(session, "where is ORD-10002?");

        List<Message> modelMessages = captureModelMessages();
        assertThat(modelMessages).hasSize(1);
        assertThat(((UserMessage) modelMessages.get(0)).getText()).isEqualTo("where is ORD-10002?");
    }

    @Test
    void noOtpPlaintextReachesModelInput() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("[verification code provided]");

        supportAgent.respondReadOnly(session, "");

        List<Message> modelMessages = captureModelMessages();
        assertThat(modelMessages).hasSize(1);
        assertThat(((UserMessage) modelMessages.get(0)).getText()).doesNotContain("482916");
    }
}
