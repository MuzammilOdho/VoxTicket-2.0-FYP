package com.voxticket.verification;

import java.util.Map;

/**
 * The result of issuing (or refusing to issue) a verification challenge. The
 * stable {@link VerificationIssueCode} is the authoritative branch marker;
 * the message is retained for domain/backward compatibility and for the
 * model-facing path, but the direct-response renderer must never parse it.
 */
public record VerificationOutcome(boolean success, String message, Map<String, String> metadata, VerificationIssueCode issueCode) {

    public VerificationOutcome {
        if (issueCode == null) {
            throw new IllegalArgumentException("VerificationOutcome issue code must not be null");
        }
    }

    public static VerificationOutcome challengeIssued(String message, Map<String, String> metadata) {
        return new VerificationOutcome(true, message, metadata, VerificationIssueCode.CHALLENGE_ISSUED);
    }

    public static VerificationOutcome error(String message) {
        return new VerificationOutcome(false, message, Map.of(), VerificationIssueCode.RESEND_COOLDOWN);
    }

    public static VerificationOutcome error(VerificationIssueCode issueCode, String message) {
        return new VerificationOutcome(false, message, Map.of(), issueCode);
    }
}
