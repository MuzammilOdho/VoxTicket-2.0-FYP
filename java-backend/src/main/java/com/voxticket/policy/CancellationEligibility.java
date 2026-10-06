package com.voxticket.policy;

public record CancellationEligibility(boolean eligible, CancellationDenialReason denialReason, PaymentConsequence paymentConsequence) {

    public static CancellationEligibility eligible(PaymentConsequence consequence) {
        return new CancellationEligibility(true, null, consequence);
    }

    public static CancellationEligibility denied(CancellationDenialReason reason) {
        return new CancellationEligibility(false, reason, null);
    }
}