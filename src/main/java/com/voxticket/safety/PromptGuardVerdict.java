package com.voxticket.safety;

public record PromptGuardVerdict(boolean suspicious, String category, String matchedPattern) {

    public static PromptGuardVerdict allow() {
        return new PromptGuardVerdict(false, null, null);
    }

    public static PromptGuardVerdict flagged(String category, String matchedPattern) {
        return new PromptGuardVerdict(true, category, matchedPattern);
    }
}