package com.voxticket.verification;

/**
 * The result of a single verification attempt. The stable
 * {@link VerificationResultCode} is the authoritative branch marker; the
 * message is retained for domain/backward compatibility and for the
 * model-facing path, but the direct-response renderer must never parse it.
 */
public record VerificationResult(boolean verified, VerificationResultCode code, String message) {

    public VerificationResult {
        if (code == null) {
            throw new IllegalArgumentException("VerificationResult code must not be null");
        }
    }

    public static VerificationResult success() {
        return new VerificationResult(true, VerificationResultCode.VERIFIED, null);
    }

    public static VerificationResult error(VerificationResultCode code, String message) {
        return new VerificationResult(false, code, message);
    }
}
