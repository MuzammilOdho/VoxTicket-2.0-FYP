package com.voxticket.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Spec §45/§54. Calls the real classifier via Groq's chat-completions
 * endpoint. Groq returns a numeric maliciousness score from 0.0 to 1.0 as
 * the message content - NOT a text label - compared against a configurable
 * threshold (default 0.5). Only ever sends model + a small maxTokens to
 * this endpoint - no GPT-OSS-specific options like include_reasoning,
 * which this model does not support.
 *
 * <p>NOT independently verified end-to-end from this environment - opt-in
 * via PromptGuardConfiguration, with HeuristicPromptGuard as the always-on
 * fallback. Blank, unparseable, or out-of-range output is treated as
 * INVALID and falls back to the heuristic guard - never silently treated
 * as benign.
 */
public class GroqMlPromptGuard implements PromptGuard {

    private static final Logger log = LoggerFactory.getLogger(GroqMlPromptGuard.class);

    private final ChatClient chatClient;
    private final PromptGuard fallback;
    private final String model;
    private final double threshold;

    public GroqMlPromptGuard(ChatClient chatClient, PromptGuard fallback, String model, double threshold) {
        this.chatClient = chatClient;
        this.fallback = fallback;
        this.model = model;
        this.threshold = threshold;
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
                    .options(ChatOptions.builder().model(model).maxTokens(10))
                    .call()
                    .content();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            Double score = parseScore(raw);
            if (score == null) {
                log.warn("event=prompt_guard implementation=ml model={} outcome=invalid_score fallback=true durationMs={}", model, durationMs);
                return fallback.evaluate(userInput);
            }
            boolean malicious = score >= threshold;
            log.info("event=prompt_guard implementation=ml model={} score={} threshold={} suspicious={} fallback=false durationMs={}",
                    model, score, threshold, malicious, durationMs);
            return malicious ? PromptGuardVerdict.flagged("ML_CLASSIFIER", String.valueOf(score)) : PromptGuardVerdict.allow();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("event=prompt_guard implementation=ml model={} outcome=error errorType={} fallback=true durationMs={}",
                    model, e.getClass().getSimpleName(), durationMs);
            return fallback.evaluate(userInput);
        }
    }

    /** Returns the parsed score, or null if blank/unparseable/outside [0.0, 1.0] - caller must fall back, never assume benign. */
  public static Double parseScore(String raw) {
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