package com.voxticket.agent;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

/**
 * The single, narrow boundary where provider-specific construction lives.
 *
 * <pre>
 * TierChatClientRegistry -> resolves tier -> provider -> this factory
 * ProviderChatModelFactory
 *   ├── GOOGLE   -> GoogleGenAiChatModel  (native Google GenAI SDK, GEMINI_API_KEY)
 *   ├── GROQ     -> OpenAiChatModel       (OpenAI-compatible, https://api.groq.com/openai/v1)
 *   └── CEREBRAS -> OpenAiChatModel       (OpenAI-compatible, https://api.cerebras.ai/v1)
 * </pre>
 *
 * <p>Provider-specific request fields are isolated to the provider that accepts
 * them. In particular, Groq's {@code include_reasoning} is built ONLY for the
 * Groq path - it previously leaked into every provider's options and caused
 * Google to fail with {@code 400 INVALID_ARGUMENT: Unknown name
 * "include_reasoning"}. Cerebras gets only standard OpenAI-compatible fields.
 * Google uses the native GenAI SDK, which has no such field at all.
 *
 * <p>Nothing outside this class branches on {@link AiProvider}: not
 * SupportAgent, not metrics, not business logic.
 */
@Component
public class ProviderChatModelFactory {

    /**
     * Builds the fully configured chat model for one tier's provider.
     * Construction performs no network I/O - the model is ready for
     * {@code ChatClient.builder(chatModel).build()} by the caller.
     */
    public ChatModel chatModelFor(
            AiProvider provider,
            ProviderProperties providerProperties,
            TierChatProperties tierProperties,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        return switch (provider) {
            case GOOGLE -> googleChatModel(providerProperties, tierProperties, observationRegistry);
            case GROQ -> openAiChatModel(providerProperties, tierProperties, groqChatOptions(tierProperties), observationRegistry, meterRegistry);
            case CEREBRAS -> openAiChatModel(providerProperties, tierProperties, cerebrasChatOptions(tierProperties), observationRegistry, meterRegistry);
        };
    }

    /**
     * The provider-specific default options for a tier. Exposed for tests -
     * business logic never calls this; it goes through
     * {@link #chatModelFor}.
     */
    public ChatOptions chatOptionsFor(AiProvider provider, TierChatProperties tierProperties) {
        return switch (provider) {
            case GOOGLE -> googleChatOptions(tierProperties);
            case GROQ -> groqChatOptions(tierProperties);
            case CEREBRAS -> cerebrasChatOptions(tierProperties);
        };
    }

    // ------------------------------------------------------------------
    // GOOGLE - native Google GenAI SDK
    // ------------------------------------------------------------------

    private GoogleGenAiChatModel googleChatModel(
            ProviderProperties providerProperties,
            TierChatProperties tierProperties,
            ObservationRegistry observationRegistry) {
        // API-key mode: the Gemini endpoint is built into the SDK, so no base
        // URL is configured (and none is required - see
        // TierChatClientRegistry.requireUsableProvider).
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(Math.toIntExact(Duration.ofSeconds(tierProperties.timeoutSeconds()).toMillis()))
                .build();
        Client genAiClient = Client.builder()
                .apiKey(providerProperties.apiKey())
                .httpOptions(httpOptions)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .options(googleChatOptions(tierProperties))
                .observationRegistry(observationRegistry)
                .build();
    }

    private GoogleGenAiChatOptions googleChatOptions(TierChatProperties tier) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                .model(resolveGoogleModel(tier.model()))
                .maxOutputTokens(tier.maxOutputTokens());
        builder.temperature(tier.temperature());
        // Spec: never expose chain-of-thought. The native GenAI API only
        // returns thoughts when explicitly asked, so this is belt-and-braces.
        builder.includeThoughts(false);
        GoogleGenAiThinkingLevel thinkingLevel = googleThinkingLevel(tier.reasoningEffort());
        if (thinkingLevel != null) {
            builder.thinkingLevel(thinkingLevel);
        }
        return builder.build();
    }

    /**
     * The configured model must be a model the Spring AI Google integration
     * actually supports - {@link GoogleGenAiChatModel.ChatModel} is an enum,
     * so an unknown ID fails fast here instead of at the first request.
     */
    static GoogleGenAiChatModel.ChatModel resolveGoogleModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Google tier has no model configured - set 'voxticket.ai.tiers.<tier>.model' "
                    + "to one of " + supportedGoogleModels());
        }
        String normalized = model.trim().toUpperCase().replace('-', '_').replace('/', '_').replace('.', '_');
        try {
            return GoogleGenAiChatModel.ChatModel.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported Google model '" + model + "' - must be one of " + supportedGoogleModels());
        }
    }

    private static String supportedGoogleModels() {
        return Arrays.stream(GoogleGenAiChatModel.ChatModel.values())
                .map(GoogleGenAiChatModel.ChatModel::getValue)
                .sorted()
                .toList()
                .toString();
    }

    /**
     * VoxTicket's {@code reasoning-effort} for Google: {@code none} (default)
     * leaves the model's thinking at its default with thoughts suppressed;
     * {@code low}/{@code medium}/{@code high} map to the native thinking
     * levels. Thoughts are never returned (see {@link #googleChatOptions}).
     */
    static GoogleGenAiThinkingLevel googleThinkingLevel(String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.equalsIgnoreCase("none")) {
            return null;
        }
        return switch (reasoningEffort.toLowerCase()) {
            case "low" -> GoogleGenAiThinkingLevel.LOW;
            case "medium" -> GoogleGenAiThinkingLevel.MEDIUM;
            case "high" -> GoogleGenAiThinkingLevel.HIGH;
            default -> throw new IllegalArgumentException(
                    "Unsupported reasoning-effort '" + reasoningEffort + "' for provider GOOGLE - expected none|low|medium|high");
        };
    }

    // ------------------------------------------------------------------
    // GROQ - OpenAI-compatible; Groq-specific fields live ONLY here
    // ------------------------------------------------------------------

    private OpenAiChatOptions groqChatOptions(TierChatProperties tier) {
        var builder = OpenAiChatOptions.builder()
                .model(tier.model())
                .temperature(tier.temperature())
                .maxCompletionTokens(tier.maxOutputTokens())
                .timeout(Duration.ofSeconds(tier.timeoutSeconds()));
        // Groq-only: omit reasoning content from the response.
        builder.extraBody(Map.of("include_reasoning", false));
        // VoxTicket "none" means: omit reasoning_effort entirely (plus the
        // include_reasoning=false above). low/medium/high are sent natively.
        if (tier.reasoningEffort() != null && !tier.reasoningEffort().equalsIgnoreCase("none")) {
            builder.reasoningEffort(tier.reasoningEffort());
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // CEREBRAS - OpenAI-compatible; standard fields only
    // ------------------------------------------------------------------

    private OpenAiChatOptions cerebrasChatOptions(TierChatProperties tier) {
        // Cerebras accepts standard OpenAI-compatible fields. Groq's
        // include_reasoning is deliberately NOT sent here.
        var builder = OpenAiChatOptions.builder()
                .model(tier.model())
                .temperature(tier.temperature())
                .maxCompletionTokens(tier.maxOutputTokens())
                .timeout(Duration.ofSeconds(tier.timeoutSeconds()));
        if (tier.reasoningEffort() != null && !tier.reasoningEffort().equalsIgnoreCase("none")) {
            builder.reasoningEffort(tier.reasoningEffort());
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Shared OpenAI-compatible model construction (Groq + Cerebras)
    // ------------------------------------------------------------------

    private OpenAiChatModel openAiChatModel(
            ProviderProperties providerProperties,
            TierChatProperties tierProperties,
            OpenAiChatOptions options,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        // The supported Spring AI 2.0.1 construction path (also used by the
        // OpenAI auto-configuration): builds the official OpenAI Java clients
        // with their HTTP layer, then the chat model on top of them.
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
        return OpenAiChatModel.builder()
                .openAiClient(openAIClient)
                .openAiClientAsync(openAIClientAsync)
                .options(options)
                .build();
    }
}
