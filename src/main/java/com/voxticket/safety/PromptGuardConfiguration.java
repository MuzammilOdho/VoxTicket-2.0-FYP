package com.voxticket.safety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the real ML classifier in behind the existing PromptGuard interface,
 * opt-in via voxticket.safety.prompt-guard.provider=ml (default: heuristic
 * only). See GroqMlPromptGuard's javadoc for why this stays opt-in until
 * verified against a live account.
 */
@Configuration
public class PromptGuardConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "voxticket.safety.prompt-guard", name = "provider", havingValue = "ml")
    public PromptGuard mlPromptGuard(
            ChatClient.Builder chatClientBuilder,
            HeuristicPromptGuard fallback,
            @Value("${voxticket.safety.prompt-guard.ml-model:meta-llama/llama-prompt-guard-2-86m}") String model) {
        return new GroqMlPromptGuard(chatClientBuilder.build(), fallback, model);
    }
}