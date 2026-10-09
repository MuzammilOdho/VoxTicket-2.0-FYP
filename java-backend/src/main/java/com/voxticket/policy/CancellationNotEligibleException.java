package com.voxticket.policy;

public class CancellationNotEligibleException extends RuntimeException {

    private final CancellationDenialReason reason;

    public CancellationNotEligibleException(String orderNumber, CancellationDenialReason reason) {
        super("Order " + orderNumber + " is not eligible for cancellation: " + reason);
        this.reason = reason;
    }

    public CancellationDenialReason getReason() {
        return reason;
    }
}