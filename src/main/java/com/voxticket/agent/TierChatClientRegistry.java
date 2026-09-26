package com.voxticket.agent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.stereotype.Component;

/**
 * Phase 1 provider/model wiring.
 *
 * <pre>
 * ModelSelector          -> decides the TIER only (never the provider)
 * AiTiersProperties      -> tier configuration: provider + model + options
 * AiProvidersProperties  -> provider connection details (base URL, API key)
 * ProviderChatModelFactory -> builds the provider-specific ChatModel
 * TierChatClientRegistry -> one configured ChatClient per tier (this class)
 * </pre>
 *
 * <p>GOOGLE tiers are served by the native Spring AI Google GenAI integration
 * ({@code GoogleGenAiChatModel} on the official GenAI SDK); GROQ and CEREBRAS
 * tiers by Spring AI {@code OpenAiChatModel} pointed at their OpenAI-compatible
 * endpoints. There is deliberately no fallback, no retry routing, no replay
 * and no failure classifier here - a provider/model failure surfaces as an
 * exception and the caller (SupportAgent) answers with the existing safe
 * generic failure response.
 *
 * <p>Validation is fail-fast at startup: a tier whose provider is disabled or
 * has no API key prevents the application from starting, with a message that
 * names the exact property and environment variable. A base URL is required
 * only for providers that need one ({@link AiProvider#requiresBaseUrl()}) -
 * GOOGLE in native API-key mode does not. Providers no tier references are
 * not validated, so an unconfigured Google key is fine while no tier uses
 * Google.
 */
@Component
public class TierChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(TierChatClientRegistry.class);

    /** What a tier resolved to - carried for metrics and audit logging. */
    public record TierResolution(AiProvider provider, String model) {
    }

    private final Map<ModelTier, ChatClient> clients = new EnumMap<>(ModelTier.class);
    private final Map<ModelTier, TierResolution> resolutions = new EnumMap<>(ModelTier.class);
    private final ToolCallingAdvisor toolCallingAdvisor;

    public TierChatClientRegistry(
            AiProvidersProperties providers,
            AiTiersProperties tiers,
            ProviderChatModelFactory chatModelFactory,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            SanitizedToolExecutionExceptionProcessor exceptionProcessor) {
        // One shared tool-calling advisor for every tier, configured here -
        // never per-prompt in SupportAgent. Because a ToolAdvisor is already
        // present, the framework skips its own default auto-registration, so
        // each turn runs exactly one ToolCallingAdvisor. The 3-arg manager
        // constructor leaves tool-name resolution fallback disabled.
        var toolCallingManager = new DefaultToolCallingManager(
                observationRegistry, new DelegatingToolCallbackResolver(List.of()), exceptionProcessor);
        this.toolCallingAdvisor = ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build();
        for (ModelTier tier : ModelTier.values()) {
            TierChatProperties tierProperties = tiers.forTier(tier);
            AiProvider provider = tierProperties.provider();
            ProviderProperties providerProperties = requireUsableProvider(tier, provider, providers);

            ChatClient chatClient = ChatClient.builder(
                            chatModelFactory.chatModelFor(provider, providerProperties, tierProperties, observationRegistry, meterRegistry))
                    .defaultAdvisors(toolCallingAdvisor)
                    .build();
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

    /**
     * The shared tool-calling advisor configured on every tier's ChatClient.
     * Exposed for tests and diagnostics; agent turns must not attach it
     * per-prompt - it is already a default advisor on each client.
     */
    public ToolCallingAdvisor toolCallingAdvisor() {
        return toolCallingAdvisor;
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
        if (provider.requiresBaseUrl() && (properties.baseUrl() == null || properties.baseUrl().isBlank())) {
            throw new IllegalStateException("Model tier " + tier + " is configured with provider " + provider
                    + ", but '" + section + ".base-url' is empty.");
        }
        return properties;
    }
}
