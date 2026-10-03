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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import reactor.core.publisher.Flux;

/**
 * The streaming agent variants forward the provider's native deltas
 * unchanged while preserving the blocking path's outcome semantics: full
 * text assembly, blank fallback, and safe model-error recovery.
 */
class SupportAgentStreamingTest {

    private ChatModel chatModel;
    private SupportAgent agent;

    @BeforeEach
    void setUp() {
        var meterRegistry = new SimpleMeterRegistry();
        var turnMetrics = new TurnMetrics(meterRegistry);
        chatModel = mock(ChatModel.class);
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
        when(contextBuilder.buildHistory(any(), any())).thenReturn(List.of());

        agent = new SupportAgent(registry, contextBuilder, modelSelector,
                mock(CustomerOrderQueryService.class), mock(RagService.class),
                mock(ProcedureCoordinator.class), turnMetrics, mock(ConversationAuditService.class),
                new ConversationLanguageResolver());
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private AgentResponse stream(Consumer<String> sink) {
        return agent.streamResponse(ConversationSession.newSession("s1", Channel.PHONE), "hello", sink);
    }

    @Test
    void nativeDeltasAreForwardedInOrderAndAssembled() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("Hello "), textResponse("world")));

        List<String> deltas = new ArrayList<>();
        AgentResponse response = stream(deltas::add);

        assertThat(deltas).containsExactly("Hello ", "world");
        assertThat(response.text()).isEqualTo("Hello world");
        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.SUCCESS);
    }

    @Test
    void blankStreamFallsBackToSafeReprompt() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse(""), textResponse("")));

        List<String> deltas = new ArrayList<>();
        AgentResponse response = stream(deltas::add);

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.BLANK_FALLBACK);
        assertThat(deltas).containsExactly(response.text());
        assertThat(response.text()).isNotBlank();
    }

    @Test
    void streamErrorBeforeAnyDeltaEmitsSafeFallback() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("provider exploded")));

        List<String> deltas = new ArrayList<>();
        AgentResponse response = stream(deltas::add);

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.MODEL_ERROR);
        assertThat(response.text()).contains("try again in a moment");
        assertThat(deltas).containsExactly(response.text());
    }

    @Test
    void streamErrorAfterPartialDeltasKeepsWhatWasSpoken() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(textResponse("partial ")),
                Flux.error(new RuntimeException("provider exploded"))));

        List<String> deltas = new ArrayList<>();
        AgentResponse response = stream(deltas::add);

        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.MODEL_ERROR);
        assertThat(response.text()).isEqualTo("partial ");
        assertThat(deltas).containsExactly("partial ");
    }

    @Test
    void guardedVariantStreamsToo() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(textResponse("guarded hi")));

        List<String> deltas = new ArrayList<>();
        AgentResponse response = agent.streamGuardedResponse(
                ConversationSession.newSession("s2", Channel.PHONE), "hello", deltas::add);

        assertThat(deltas).containsExactly("guarded hi");
        assertThat(response.outcome()).isEqualTo(AgentResponse.Outcome.SUCCESS);
    }
}
