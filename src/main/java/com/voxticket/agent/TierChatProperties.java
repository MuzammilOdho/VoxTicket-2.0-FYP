package com.voxticket.agent;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about ONE model tier: which provider serves it, which model to
 * use there, and how each request behaves. Bound from
 * {@code voxticket.ai.tiers.<tier>}.
 *
 * <p>Switching a tier to a different provider or model is a config-only
 * change - {@link ModelSelector} decides the tier and never knows the
 * provider, and {@link TierChatClientRegistry} builds the matching
 * {@code ChatClient} from exactly these values.
 *
 * <p>{@code reasoningEffort} is {@code none} (the default), {@code low},
 * {@code medium} or {@code high}. {@code none} keeps the long-standing
 * behavior of suppressing provider-emitted reasoning (spec: never expose
 * chain-of-thought); any other value is passed through as the OpenAI-style
 * {@code reasoning_effort} request parameter.
 */
public record TierChatProperties(
        @DefaultValue("GROQ") AiProvider provider,
        @DefaultValue("openai/gpt-oss-20b") String model,
        @DefaultValue("0.3") double temperature,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("none") String reasoningEffort,
        @DefaultValue("20") int timeoutSeconds) {
}
