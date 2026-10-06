package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationLanguageResolver;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.rag.RagService;
import com.voxticket.service.CustomerOrderQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Agent-level observability: success, blank fallback, and model error are
 * recorded under distinct metric labels, and a failure recovered with the
 * safe fallback is never recorded as a normal turn.
 */
class SupportAgentErrorMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private ChatModel chatModel;
    private SupportAgent agent;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        var turnMetrics = new TurnMetrics(meterRegistry);
        chatModel = mock(ChatModel.class);
        // The ChatClient reads model options when building the request; a
        // deep stub keeps the mock provider-agnostic without real options.
        when(chatModel.getOptions()).thenReturn(mock(ChatOptions.class, RETURNS_DEEP_STUBS));

        var factory = mock(ProviderChatModelFactory.class);
        when(factory.chatModelFor(any(), any(), any(), any(), any())).thenReturn(chatModel);

        var providers = new AiProvidersProperties(
                new ProviderProperties(true, "google-key", null),
                new ProviderProperties(true, "groq-key", "https://api.groq.com/openai/v1"),
                new ProviderProperties(true, "cerebras-key", "https://api.cerebras.ai/v1"));
        var tiers = new AiTiersProperties(
                new TierChatProperties(AiProvider.GOOGLE, "gemini-3.6-flash", 0.3, 1024, "none", 20, 0),
                new TierChatProperties(AiProvider.GROQ, "openai/gpt-oss-20b", 0.3, 1024, "none", 20, 0));
        var registry = new TierChatClientRegistry(providers, tiers, factory,
                ObservationRegistry.NOOP, meterRegistry,
                new SanitizedToolExecutionExceptionProcessor(turnMetrics), new ToolCallLimitsProperties(5, 10, ToolCallLimitBehavior.THROW));

        var modelSelector = mock(ModelSelector.class);
        when(modelSelector.select(any(), any())).thenReturn(new ModelSelectionResult(ModelTier.TIER_1, "test"));

        var contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.buildHistory(any())).thenReturn(List.of());

        agent = new SupportAgent(registry, contextBuilder, modelSelector,
                mock(CustomerOrderQueryService.class), mock(RagService.class),
                mock(ProcedureCoordinator.class), turnMetrics, mock(ConversationAuditService.class),
                new ConversationLanguageResolver());
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private double llmCallCount(String outcome) {
        var timer = meterRegistry.find("voxticket.llm.call.duration").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void modelErrorReturnsSafeFallbackAndRecordsModelError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("provider exploded"));

        AgentResponse response = agent.respond(ConversationSession.newSession("s1", Channel.CHAT), "hello");

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.MODEL_ERROR);
        assertThat(response.text()).contains("try again in a moment");
        assertThat(llmCallCount("model_error")).isEqualTo(1);
        assertThat(llmCallCount("success")).isZero();
        assertThat(llmCallCount("blank_fallback")).isZero();
    }

    @Test
    void quotaExceededReturnsSafeFallbackWithModelError() {
        // A provider-side quota/rate-limit failure (e.g. HTTP 429) is a model error like any
        // other: the existing safe generic recovery is returned, with no retry and no fallback
        // to another provider.
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 Too Many Requests: quota exceeded for model"));

        AgentResponse response = agent.respond(ConversationSession.newSession("s4", Channel.CHAT), "where is my order");

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.MODEL_ERROR);
        assertThat(response.text()).isEqualTo("I'm having trouble processing that right now - please try again in a moment.");
        assertThat(llmCallCount("model_error")).isEqualTo(1);
        assertThat(llmCallCount("success")).isZero();
    }

    @Test
    void successRecordsSuccessMetric() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("Your order is on its way."));

        AgentResponse response = agent.respond(ConversationSession.newSession("s2", Channel.CHAT), "where is my order");

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.SUCCESS);
        assertThat(response.text()).isEqualTo("Your order is on its way.");
        assertThat(llmCallCount("success")).isEqualTo(1);
        assertThat(llmCallCount("model_error")).isZero();
        assertThat(llmCallCount("blank_fallback")).isZero();
    }

    @Test
    void blankModelOutputUsesBlankFallbackAndRecordsIt() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("   "));

        AgentResponse response = agent.respond(ConversationSession.newSession("s3", Channel.CHAT), "hello");

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.BLANK_FALLBACK);
        assertThat(response.text()).isEqualTo("Sorry, could you say that again?");
        assertThat(llmCallCount("blank_fallback")).isEqualTo(1);
        assertThat(llmCallCount("success")).isZero();
        assertThat(llmCallCount("model_error")).isZero();
    }

    @Test
    void recoveredAgentErrorIsNeverRecordedAsNormalTurn() {
        assertThat(AgentResponse.Outcome.MODEL_ERROR.toTurnLabel("normal")).isEqualTo("agent_error_recovered");
        assertThat(AgentResponse.Outcome.BLANK_FALLBACK.toTurnLabel("normal")).isEqualTo("blank_fallback");
        assertThat(AgentResponse.Outcome.BLANK_FALLBACK.toTurnLabel("verification_unclear"))
                .isEqualTo("blank_fallback");
        assertThat(AgentResponse.Outcome.SUCCESS.toTurnLabel("normal")).isEqualTo("normal");
        assertThat(AgentResponse.Outcome.SUCCESS.toTurnLabel("verification_unclear"))
                .isEqualTo("verification_unclear");
    }
}
