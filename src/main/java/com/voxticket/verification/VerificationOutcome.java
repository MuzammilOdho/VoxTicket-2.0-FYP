package com.voxticket.verification;

import java.util.Map;

public record VerificationOutcome(boolean success, String message, Map<String, String> metadata) {

    public static VerificationOutcome challengeIssued(String message, Map<String, String> metadata) {
        return new VerificationOutcome(true, message, metadata);
    }

    public static VerificationOutcome error(String message) {
        return new VerificationOutcome(false, message, Map.of());
    }
}