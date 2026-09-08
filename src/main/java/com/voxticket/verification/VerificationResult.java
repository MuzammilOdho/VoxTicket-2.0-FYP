package com.voxticket.verification;

public record VerificationResult(boolean verified, String message) {

    public static VerificationResult success() {
        return new VerificationResult(true, null);
    }

    public static VerificationResult error(String message) {
        return new VerificationResult(false, message);
    }
}