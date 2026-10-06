package com.voxticket.policy;

public record ReturnEligibility(boolean eligible, ReturnDenialReason denialReason, int maxReturnableQuantity) {

    public static ReturnEligibility eligible(int maxReturnableQuantity) {
        return new ReturnEligibility(true, null, maxReturnableQuantity);
    }

    public static ReturnEligibility denied(ReturnDenialReason reason) {
        return new ReturnEligibility(false, reason, 0);
    }
}