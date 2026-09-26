package com.voxticket.agent;

import java.time.Duration;
import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * The one place OpenAI-compatible chat option construction lives. Callers
 * only ever ask forTier(...) - they never build provider-specific options
 * themselves, so provider-specific behavior (or the provider entirely) can
 * change without touching agent/business logic.
 *
 * <p>Returns built {@link OpenAiChatOptions} (the tier's defaults, baked into
 * the tier's ChatModel by {@link TierChatClientRegistry}) - per-request
 * overrides are not needed because each tier already has its own ChatClient.
 *
 * <p>Reasoning: {@code reasoningEffort=none} (the default) preserves the
 * long-standing spec behavior of never exposing chain-of-thought - Groq
 * honors {@code include_reasoning=false} by omitting reasoning from the
 * response, and other OpenAI-compatible endpoints ignore the unknown field.
 * Any other value is passed through natively as {@code reasoning_effort}.
 */
@Component
public class ChatOptionsFactory {

    public OpenAiChatOptions forTier(TierChatProperties tier) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(tier.model())
                .temperature(tier.temperature())
                .maxCompletionTokens(tier.maxOutputTokens())
                .timeout(Duration.ofSeconds(tier.timeoutSeconds()));
        if (tier.reasoningEffort() == null || tier.reasoningEffort().equalsIgnoreCase("none")) {
            builder.extraBody(Map.of("include_reasoning", false));
        } else {
            builder.reasoningEffort(tier.reasoningEffort());
        }
        return builder.build();
    }
}
