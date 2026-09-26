package com.voxticket.safety;

import com.voxticket.agent.AiProvider;
import com.voxticket.agent.AiProvidersProperties;
import com.voxticket.agent.ProviderChatModelFactory;
import com.voxticket.agent.ProviderProperties;
import com.voxticket.agent.TierChatProperties;
import com.voxticket.observability.TurnMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class PromptGuardConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "voxticket.safety.prompt-guard", name = "provider", havingValue = "ml")
    public PromptGuard mlPromptGuard(
            ProviderChatModelFactory chatModelFactory, AiProvidersProperties providers,
            HeuristicPromptGuard fallback, PromptGuardProperties properties, TurnMetrics turnMetrics,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        // The opt-in ML guard is Groq-specific: GroqMlPromptGuard calls a
        // Groq-hosted classifier model with a per-request model override.
        // spring.ai.model.chat=none disables every auto-configured chat model,
        // so the guard builds its own client from the GROQ provider section -
        // same endpoint and key the old auto-configured client used.
        ProviderProperties groq = requireUsableGroq(providers);
        TierChatProperties guardTier = new TierChatProperties(
                AiProvider.GROQ, properties.mlModel(), 0.7, properties.mlMaxTokens(), "none", 20);
        ChatModel guardModel = chatModelFactory.chatModelFor(
                AiProvider.GROQ, groq, guardTier, observationRegistry, meterRegistry);
        return new GroqMlPromptGuard(ChatClient.builder(guardModel).build(), fallback, properties, turnMetrics);
    }

    private ProviderProperties requireUsableGroq(AiProvidersProperties providers) {
        ProviderProperties groq = providers.forProvider(AiProvider.GROQ);
        if (!groq.enabled()) {
            throw new IllegalStateException("The ML prompt guard needs the GROQ provider, but "
                    + "'voxticket.ai.providers.groq.enabled' is false.");
        }
        if (groq.apiKey() == null || groq.apiKey().isBlank()) {
            throw new IllegalStateException("The ML prompt guard needs the GROQ provider, but no API key is "
                    + "configured ('voxticket.ai.providers.groq.api-key' is empty). Set the GROQ_API_KEY environment variable.");
        }
        if (groq.baseUrl() == null || groq.baseUrl().isBlank()) {
            throw new IllegalStateException("The ML prompt guard needs the GROQ provider, but "
                    + "'voxticket.ai.providers.groq.base-url' is empty.");
        }
        return groq;
    }
}
