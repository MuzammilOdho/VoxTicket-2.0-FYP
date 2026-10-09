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
        // The opt-in ML guard is Groq-specific: GroqMlPromptGuard calls the
        // Groq-hosted classifier model. The guard ChatClient is built on a
        // ChatModel whose defaults already target that model (see below), so
        // the guard issues no per-request options of its own. The guard model
        // rejects Groq's include_reasoning parameter, so it is built through
        // guardChatModel, whose options omit that field (tiers keep it).
        // spring.ai.model.chat=none disables every auto-configured chat model,
        // so the guard builds its own client from the GROQ provider section -
        // same endpoint and key the old auto-configured client used.
        ProviderProperties groq = requireUsableGroq(providers);
        ChatModel guardModel = chatModelFactory.guardChatModel(
                groq, guardTierProperties(properties), observationRegistry, meterRegistry);
        return new GroqMlPromptGuard(ChatClient.builder(guardModel).build(), fallback, properties, turnMetrics);
    }

    /**
     * The tier options for the ML prompt-guard classifier. Package-visible for tests.
     *
     * <p>Phase 2: temperature is 0, not a chat default. The guard expects a single
     * numeric jailbreak-probability score compared against a threshold
     * ({@link GroqMlPromptGuard#parseScore}), so any sampling noise can flip the
     * verdict for the same input - classification must be deterministic.
     */
    static TierChatProperties guardTierProperties(PromptGuardProperties properties) {
        return new TierChatProperties(
                AiProvider.GROQ, properties.mlModel(), 0.0, properties.mlMaxTokens(), "none", 20, 0);
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
