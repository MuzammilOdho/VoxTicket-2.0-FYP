package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.rag.RagService;
import com.voxticket.service.CustomerOrderQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

/**
 * SupportAgent stays provider-agnostic: it only ever receives the
 * tier-resolved ChatClient from TierChatClientRegistry. Wiring a tier to a
 * different provider (here: GOOGLE via the native GenAI SDK, GROQ via
 * OpenAI-compatible) requires no change to SupportAgent itself.
 */
class SupportAgentProviderIndependenceTest {

    @Test
    void supportAgentWorksUnchangedRegardlessOfTierProviders() {
        var providers = new AiProvidersProperties(
                new ProviderProperties(true, "google-key", null),
                new ProviderProperties(true, "groq-key", "https://api.groq.com/openai/v1"),
                new ProviderProperties(true, "cerebras-key", "https://api.cerebras.ai/v1"));
        var tiers = new AiTiersProperties(
                new TierChatProperties(AiProvider.GOOGLE, "gemini-3.6-flash", 0.3, 1024, "none", 20),
                new TierChatProperties(AiProvider.GROQ, "openai/gpt-oss-20b", 0.3, 1024, "none", 20));
        var registry = new TierChatClientRegistry(
                providers, tiers, new ProviderChatModelFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry(),
                new SanitizedToolExecutionExceptionProcessor(new TurnMetrics(new SimpleMeterRegistry())));

        var agent = new SupportAgent(
                registry, mock(ContextBuilder.class), mock(ModelSelector.class),
                mock(CustomerOrderQueryService.class), mock(RagService.class),
                mock(ProcedureCoordinator.class), mock(TurnMetrics.class), mock(ConversationAuditService.class));

        // Provider-agnostic behavior works with the real registry behind it.
        String prompt = agent.buildSystemPrompt(ConversationSession.newSession("s1", Channel.CHAT));

        assertThat(prompt).isNotBlank();
        assertThat(registry.resolutionFor(ModelTier.TIER_1).provider()).isEqualTo(AiProvider.GOOGLE);
        assertThat(registry.resolutionFor(ModelTier.TIER_2).provider()).isEqualTo(AiProvider.GROQ);
    }
}
