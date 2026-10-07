package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * ProviderChatModelFactory: each provider gets its own native construction
 * path and only the request fields that provider accepts.
 *
 * <p>Regression focus: Groq's {@code include_reasoning} previously leaked into
 * every provider's options and made Google fail with
 * {@code 400 INVALID_ARGUMENT: Unknown name "include_reasoning"}. These tests
 * pin the isolation down per provider. The same field is rejected by Groq's
 * prompt-guard classifier model with {@code 400 invalid_request_error}, so
 * the guard model is pinned to options without it.
 */
class ProviderChatModelFactoryTest {

    private final ProviderChatModelFactory factory = new ProviderChatModelFactory();

    private static TierChatProperties tier(AiProvider provider, String model, String reasoningEffort) {
        return new TierChatProperties(provider, model, 0.3, 1024, reasoningEffort, 20, 0);
    }

    private static ProviderProperties providerProps(String apiKey, String baseUrl) {
        return new ProviderProperties(true, apiKey, baseUrl);
    }

    @Test
    void googleTierBuildsGoogleGenAiChatModel() {
        var model = factory.chatModelFor(
                AiProvider.GOOGLE, providerProps("google-key", null), tier(AiProvider.GOOGLE, "gemini-3.6-flash", "none"),
                ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(model).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    void groqTierBuildsOpenAiChatModel() {
        var model = factory.chatModelFor(
                AiProvider.GROQ, providerProps("groq-key", "https://api.groq.com/openai/v1"),
                tier(AiProvider.GROQ, "openai/gpt-oss-20b", "none"),
                ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void cerebrasTierBuildsOpenAiChatModel() {
        var model = factory.chatModelFor(
                AiProvider.CEREBRAS, providerProps("cerebras-key", "https://api.cerebras.ai/v1"),
                tier(AiProvider.CEREBRAS, "gpt-oss-120b", "high"),
                ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void groqNoneOmitsReasoningEffortAndSendsIncludeReasoningFalse() {
        var options = (OpenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GROQ, tier(AiProvider.GROQ, "openai/gpt-oss-20b", "none"));

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody()).isEqualTo(Map.of("include_reasoning", false));
    }

    @Test
    void groqHighSendsReasoningEffortHighAndIncludeReasoningFalse() {
        var options = (OpenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GROQ, tier(AiProvider.GROQ, "openai/gpt-oss-20b", "high"));

        assertThat(options.getReasoningEffort()).isEqualTo("high");
        assertThat(options.getExtraBody()).isEqualTo(Map.of("include_reasoning", false));
    }

    @Test
    void cerebrasNeverReceivesIncludeReasoning() {
        for (String effort : new String[]{"none", "high"}) {
            var options = (OpenAiChatOptions) factory.chatOptionsFor(
                    AiProvider.CEREBRAS, tier(AiProvider.CEREBRAS, "gpt-oss-120b", effort));

            assertThat(options.getExtraBody() == null
                    || !options.getExtraBody().containsKey("include_reasoning"))
                    .as("cerebras reasoning-effort=%s must not carry include_reasoning", effort)
                    .isTrue();
        }
    }

    @Test
    void guardModelOmitsIncludeReasoning() {
        // meta-llama/llama-prompt-guard-2-86m rejects Groq's include_reasoning
        // with 400 invalid_request_error, so the ML prompt guard's model must
        // be built without that field while keeping the standard options.
        var model = (OpenAiChatModel) factory.guardChatModel(
                providerProps("groq-key", "https://api.groq.com/openai/v1"),
                tier(AiProvider.GROQ, "meta-llama/llama-prompt-guard-2-86m", "none"),
                ObservationRegistry.NOOP, new SimpleMeterRegistry());

        var options = (OpenAiChatOptions) model.getDefaultOptions();
        assertThat(options.getModel()).isEqualTo("meta-llama/llama-prompt-guard-2-86m");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(1024);
        assertThat(options.getExtraBody() == null
                || !options.getExtraBody().containsKey("include_reasoning"))
                .as("guard model must not carry include_reasoning")
                .isTrue();
    }

    @Test
    void googleNeverReceivesIncludeReasoning() {
        // Google goes through the native GenAI SDK: its options type has no
        // extraBody/include_reasoning concept at all, so the Groq-only field
        // cannot leak into a Google request again.
        for (String effort : new String[]{"none", "low", "medium", "high"}) {
            var options = factory.chatOptionsFor(
                    AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.6-flash", effort));

            assertThat(options).isInstanceOf(GoogleGenAiChatOptions.class);
        }
        // And across every provider, only the Groq path carries the field.
        assertThat(((OpenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GROQ, tier(AiProvider.GROQ, "openai/gpt-oss-20b", "none")))
                .getExtraBody()).containsKey("include_reasoning");
    }

    @Test
    void googleOptionsSuppressThoughtsAndMapThinkingLevels() {
        var none = (GoogleGenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.6-flash", "none"));
        assertThat(none.getIncludeThoughts()).isFalse();
        assertThat(none.getThinkingLevel()).isNull();
        assertThat(none.getModel()).isEqualTo(GoogleGenAiChatModel.ChatModel.GEMINI_3_6_FLASH.getValue());
        // Gemini 3.x deprecates temperature (Google ignores it or rejects the
        // request with 400 INVALID_ARGUMENT): the factory deliberately does
        // NOT pass the tier temperature through for 3.x models.
        assertThat(none.getTemperature()).isNotEqualTo(0.3);
        assertThat(none.getMaxOutputTokens()).isEqualTo(1024);

        // 2.x models still accept temperature, so the tier value passes through.
        var legacy = (GoogleGenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-2.0-flash", "none"));
        assertThat(legacy.getModel()).isEqualTo(GoogleGenAiChatModel.ChatModel.GEMINI_2_0_FLASH.getValue());
        assertThat(legacy.getTemperature()).isEqualTo(0.3);

        var high = (GoogleGenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.6-flash", "high"));
        assertThat(high.getIncludeThoughts()).isFalse();
        assertThat(high.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.HIGH);

        var low = (GoogleGenAiChatOptions) factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.6-flash", "low"));
        assertThat(low.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.LOW);
    }

    @Test
    void googleModelMustBeASupportedGenAiModelId() {
        assertThatThrownBy(() -> factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.8-flash", "none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gemini-3.8-flash");

        // Dashes, slashes and case are normalized to the enum.
        assertThat(ProviderChatModelFactory.resolveGoogleModel("gemini-2.5-flash"))
                .isEqualTo(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH);
    }

    @Test
    void googleRejectsUnknownReasoningEffort() {
        assertThatThrownBy(() -> factory.chatOptionsFor(
                AiProvider.GOOGLE, tier(AiProvider.GOOGLE, "gemini-3.6-flash", "ultra")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ultra");
    }
}
