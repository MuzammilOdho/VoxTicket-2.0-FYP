package com.voxticket.safety;

import com.voxticket.observability.TurnMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

public class GroqMlPromptGuard implements PromptGuard {

    private static final Logger log = LoggerFactory.getLogger(GroqMlPromptGuard.class);

    private final ChatClient chatClient;
    private final PromptGuard fallback;
    private final PromptGuardProperties properties;
    private final TurnMetrics turnMetrics;

    public GroqMlPromptGuard(ChatClient chatClient, PromptGuard fallback, PromptGuardProperties properties, TurnMetrics turnMetrics) {
        this.chatClient = chatClient;
        this.fallback = fallback;
        this.properties = properties;
        this.turnMetrics = turnMetrics;
    }

    @Override
    public PromptGuardVerdict evaluate(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return PromptGuardVerdict.allow();
        }
        long start = System.nanoTime();
        try {
            String raw = chatClient.prompt()
                    .user(userInput)
                    .options(ChatOptions.builder().model(properties.mlModel()).maxTokens(properties.mlMaxTokens()))
                    .call()
                    .content();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            Double score = parseScore(raw);
            if (score == null) {
                log.warn("event=prompt_guard implementation=ml model={} outcome=invalid_score fallback=true durationMs={}", properties.mlModel(), durationMs);
                turnMetrics.recordPromptGuardOutcome(Duration.ofMillis(durationMs), "ml", false, true);
                return fallback.evaluate(userInput);
            }
            boolean malicious = score >= properties.mlThreshold();
            log.info("event=prompt_guard implementation=ml model={} score={} threshold={} suspicious={} fallback=false durationMs={}",
                    properties.mlModel(), score, properties.mlThreshold(), malicious, durationMs);
            turnMetrics.recordPromptGuardOutcome(Duration.ofMillis(durationMs), "ml", malicious, false);
            return malicious ? PromptGuardVerdict.flagged("ML_CLASSIFIER", String.valueOf(score)) : PromptGuardVerdict.allow();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("event=prompt_guard implementation=ml model={} outcome=error errorType={} fallback=true durationMs={}",
                    properties.mlModel(), e.getClass().getSimpleName(), durationMs);
            turnMetrics.recordPromptGuardOutcome(Duration.ofMillis(durationMs), "ml", false, true);
            return fallback.evaluate(userInput);
        }
    }

    static Double parseScore(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double score = Double.parseDouble(raw.trim());
            return (score < 0.0 || score > 1.0) ? null : score;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}