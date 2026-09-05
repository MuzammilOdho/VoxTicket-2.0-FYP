package com.voxticket.safety;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Spec §45/§54. Calls the real classifier via Groq's chat-completions
 * endpoint (confirmed reachable that way). NOT independently verified
 * end-to-end from this environment - wired in opt-in (see
 * PromptGuardConfiguration) with HeuristicPromptGuard as the always-on
 * fallback.
 *
 * <p>FIX: unrecognized/blank classifier output now falls back to the
 * heuristic guard instead of being treated as benign. The previous version
 * only checked for malicious-looking labels and defaulted to ALLOW for
 * anything else, including garbled or unexpected output - a fail-open bug.
 * {@link #classify(String)} now returns a nullable Boolean: TRUE
 * (malicious), FALSE (explicitly benign), or null (unrecognized - caller
 * must fall back, never assume benign).
 *
 * <p>Only ever sends model + a small maxTokens to this endpoint - no
 * GPT-OSS-specific options like include_reasoning, which this model does
 * not support and was never designed for.
 */
public class GroqMlPromptGuard implements PromptGuard {

    private static final Logger log = LoggerFactory.getLogger(GroqMlPromptGuard.class);
    private static final Set<String> MALICIOUS_LABELS = Set.of("malicious", "label_1", "unsafe", "injection", "jailbreak");
    private static final Set<String> BENIGN_LABELS = Set.of("benign", "label_0", "safe");

    private final ChatClient chatClient;
    private final PromptGuard fallback;
    private final String model;

    public GroqMlPromptGuard(ChatClient chatClient, PromptGuard fallback, String model) {
        this.chatClient = chatClient;
        this.fallback = fallback;
        this.model = model;
    }

    @Override
    public PromptGuardVerdict evaluate(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return PromptGuardVerdict.allow();
        }
        long start = System.nanoTime();
        try {
            String rawLabel = chatClient.prompt()
                    .user(userInput)
                    .options(ChatOptions.builder().model(model).maxTokens(10))
                    .call()
                    .content();
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            Boolean classification = classify(rawLabel);
            if (classification == null) {
                log.warn("event=prompt_guard implementation=ml model={} outcome=unrecognized_label fallback=true durationMs={}",
                        model, durationMs);
                return fallback.evaluate(userInput);
            }
            log.info("event=prompt_guard implementation=ml model={} suspicious={} fallback=false durationMs={}",
                    model, classification, durationMs);
            return classification ? PromptGuardVerdict.flagged("ML_CLASSIFIER", null) : PromptGuardVerdict.allow();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("event=prompt_guard implementation=ml model={} outcome=error errorType={} fallback=true durationMs={}",
                    model, e.getClass().getSimpleName(), durationMs);
            return fallback.evaluate(userInput);
        }
    }

    /** Returns TRUE (malicious), FALSE (benign), or null (unrecognized - never treat as benign). */
    static Boolean classify(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return null;
        }
        String normalized = rawLabel.trim().toLowerCase(Locale.ROOT);
        if (MALICIOUS_LABELS.stream().anyMatch(normalized::contains)) {
            return Boolean.TRUE;
        }
        if (BENIGN_LABELS.stream().anyMatch(normalized::contains)) {
            return Boolean.FALSE;
        }
        return null;
    }
}