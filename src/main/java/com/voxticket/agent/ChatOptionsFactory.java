package com.voxticket.agent;

import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * The one place Groq/OpenAI-compatible-specific chat option construction
 * lives. SupportAgent only ever calls forModel(...) - it never builds
 * provider-specific options itself, so provider-specific behavior (or the
 * provider entirely) can change without touching agent/business logic.
 *
 * <p>Returns the BUILDER, not a built OpenAiChatOptions - ChatClient's
 * .options(...) requires the builder itself (a lesson learned the hard way
 * earlier in this project).
 */
@Component
public class ChatOptionsFactory {

    private final ModelTierProperties properties;

    public ChatOptionsFactory(ModelTierProperties properties) {
        this.properties = properties;
    }

    public OpenAiChatOptions.Builder forModel(String model) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model).temperature(properties.temperature());
        if (properties.suppressReasoning()) {
            builder.extraBody(Map.of("include_reasoning", false));
        }
        return builder;
    }
}