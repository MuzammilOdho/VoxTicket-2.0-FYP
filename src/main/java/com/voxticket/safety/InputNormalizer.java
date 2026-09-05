package com.voxticket.safety;

import org.springframework.stereotype.Component;

/**
 * Spec §45 pipeline: "input normalization" runs before PromptGuard.
 * Oversized input is rejected outright, never silently truncated - cutting
 * a message off mid-thought could change or lose its meaning in a way the
 * customer has no way of knowing happened.
 */
@Component
public class InputNormalizer {

    private static final int MAX_LENGTH = 4000;

    public NormalizationResult normalize(String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (text.length() > MAX_LENGTH) {
            return NormalizationResult.rejected(text.length());
        }
        return NormalizationResult.accepted(text);
    }
}