package com.voxticket.agent;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

/**
 * Phase 1 provider/model wiring.
 *
 * <pre>
 * ModelSelector          -> decides the TIER only (never the provider)
 * AiTiersProperties      -> tier configuration: provider + model + options
 * AiProvidersProperties  -> provider connection details (base URL, API key)
 * TierChatClientRegistry -> builds one configured ChatClient per tier (this class)
 * </pre>
 *
 * <p>All three providers (GOOGLE, GROQ, CEREBRAS) expose an OpenAI-compatible
 * chat-completions API, so every tier is served by a Spring AI
 * {@code OpenAiChatModel} built on the official OpenAI Java client pointed at
 * the provider's base URL with the provider's API key. There is deliberately
 * no fallback, no retry routing, no replay and no failure classifier here -
 * a provider/model failure surfaces as an exception and the caller
 * (SupportAgent) answers with the existing safe generic failure response.
 *
 * <p>Validation is fail-fast at startup: a tier whose provider is disabled,
 * has no API key, or has no base URL prevents the application from starting,
 * with a message that names the exact property and environment variable.
 * Providers no tier references are not validated, so an unconfigured Google
 * key is fine while no tier uses Google.
 */
@Component
public class TierChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(TierChatClientRegistry.class);

    /** What a tier resolved to - carried for metrics and audit logging. */
    public record TierResolution(AiProvider provider, String model) {
    }

    private final Map<ModelTier, ChatClient> clients = new EnumMap<>(ModelTier.class);
    private final Map<ModelTier, TierResolution> resolutions = new EnumMap<>(ModelTier.class);

    public TierChatClientRegistry(
            AiProvidersProperties providers,
            AiTiersProperties tiers,
            ChatOptionsFactory chatOptionsFactory,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        for (ModelTier tier : ModelTier.values()) {
            TierChatProperties tierProperties = tiers.forTier(tier);
            AiProvider provider = tierProperties.provider();
            ProviderProperties providerProperties = requireUsableProvider(tier, provider, providers);

            ChatClient chatClient = buildClient(providerProperties, tierProperties, chatOptionsFactory, observationRegistry, meterRegistry);
            clients.put(tier, chatClient);
            resolutions.put(tier, new TierResolution(provider, tierProperties.model()));
            log.info("event=tier_chat_client_ready tier={} provider={} model={} timeoutSeconds={}",
                    tier, provider, tierProperties.model(), tierProperties.timeoutSeconds());
        }
    }

    /** The fully configured chat client for a tier - resolved purely from configuration. */
    public ChatClient clientFor(ModelTier tier) {
        ChatClient client = clients.get(tier);
        if (client == null) {
            throw new IllegalStateException("No chat client configured for model tier " + tier);
        }
        return client;
    }

    /** Provider and model a tier resolved to - for metrics, audit and logging. Never exposes keys. */
    public TierResolution resolutionFor(ModelTier tier) {
        TierResolution resolution = resolutions.get(tier);
        if (resolution == null) {
            throw new IllegalStateException("No chat client configured for model tier " + tier);
        }
        return resolution;
    }

    private ProviderProperties requireUsableProvider(ModelTier tier, AiProvider provider, AiProvidersProperties providers) {
        ProviderProperties properties = providers.forProvider(provider);
        String section = "voxticket.ai.providers." + provider.configKey();
        if (!properties.enabled()) {
            throw new IllegalStateException("Model tier " + tier + " is configured with provider " + provider
                    + ", but '" + section + ".enabled' is false. Enable the provider or point the tier at a different provider.");
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Model tier " + tier + " is configured with provider " + provider
                    + ", but no API key is configured ('" + section + ".api-key' is empty). Set the "
                    + provider.environmentVariable() + " environment variable.");
        }
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new IllegalStateException("Model tier " + tier + " is configured with provider " + provider
                    + ", but '" + section + ".base-url' is empty.");
        }
        return properties;
    }

    private ChatClient buildClient(
            ProviderProperties providerProperties,
            TierChatProperties tierProperties,
            ChatOptionsFactory chatOptionsFactory,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        // The supported Spring AI 2.0.1 construction path (also used by the
        // OpenAI auto-configuration): builds the official OpenAI Java clients
        // with their HTTP layer, then the chat model on top of them.
        // The model builder always needs the async client; the sync one is
        // supplied alongside it for completeness.
        OpenAIClient openAIClient = OpenAiSetup.setupSyncClient(
                providerProperties.baseUrl(),
                providerProperties.apiKey(),
                null, // credential
                null, // microsoftDeploymentName
                null, // microsoftFoundryServiceVersion
                null, // organizationId
                false, // microsoftFoundry
                false, // gitHubModels
                null, // project
                Duration.ofSeconds(tierProperties.timeoutSeconds()),
                3, // maxRetries - matches the Spring AI auto-configuration default
                null, // proxy
                Map.of(), // customHeaders
                observationRegistry,
                meterRegistry,
                List.of()); // httpClientBuilderCustomizers
        OpenAIClientAsync openAIClientAsync = OpenAiSetup.setupAsyncClient(
                providerProperties.baseUrl(),
                providerProperties.apiKey(),
                null, // credential
                null, // microsoftDeploymentName
                null, // microsoftFoundryServiceVersion
                null, // organizationId
                false, // microsoftFoundry
                false, // gitHubModels
                null, // project
                Duration.ofSeconds(tierProperties.timeoutSeconds()),
                3, // maxRetries - matches the Spring AI auto-configuration default
                null, // proxy
                Map.of(), // customHeaders
                observationRegistry,
                meterRegistry,
                List.of()); // httpClientBuilderCustomizers
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .openAiClientAsync(openAIClientAsync)
                .options(chatOptionsFactory.forTier(tierProperties))
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
