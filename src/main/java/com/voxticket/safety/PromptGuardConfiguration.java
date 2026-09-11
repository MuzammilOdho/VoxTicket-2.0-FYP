package com.voxticket.safety;

import com.voxticket.observability.TurnMetrics;
import org.springframework.ai.chat.client.ChatClient;
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
            ChatClient.Builder chatClientBuilder, HeuristicPromptGuard fallback, PromptGuardProperties properties, TurnMetrics turnMetrics) {
        return new GroqMlPromptGuard(chatClientBuilder.build(), fallback, properties, turnMetrics);
    }
}