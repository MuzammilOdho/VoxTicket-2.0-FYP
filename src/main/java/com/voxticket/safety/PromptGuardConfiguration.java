package com.voxticket.safety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
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
            ChatClient.Builder chatClientBuilder,
            HeuristicPromptGuard fallback,
            @Value("${voxticket.safety.prompt-guard.ml-model:meta-llama/llama-prompt-guard-2-86m}") String model,
            @Value("${voxticket.safety.prompt-guard.ml-threshold:0.5}") double threshold) {
        return new GroqMlPromptGuard(chatClientBuilder.build(), fallback, model, threshold);
    }
}