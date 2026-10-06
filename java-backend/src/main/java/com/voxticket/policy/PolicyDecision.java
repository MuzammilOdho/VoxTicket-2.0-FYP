package com.voxticket.policy;

public record PolicyDecision(PolicyOutcome outcome, String reason) {

    public static PolicyDecision allow() {
        return new PolicyDecision(PolicyOutcome.ALLOW, null);
    }

    public static PolicyDecision requireVerification(String reason) {
        return new PolicyDecision(PolicyOutcome.REQUIRE_VERIFICATION, reason);
    }

    public static PolicyDecision requireConfirmation() {
        return new PolicyDecision(PolicyOutcome.REQUIRE_CONFIRMATION, null);
    }
}