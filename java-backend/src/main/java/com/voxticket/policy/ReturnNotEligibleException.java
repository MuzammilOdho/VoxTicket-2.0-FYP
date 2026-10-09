package com.voxticket.policy;

public class ReturnNotEligibleException extends RuntimeException {

    private final ReturnDenialReason reason;

    public ReturnNotEligibleException(String orderNumber, ReturnDenialReason reason) {
        super("Order " + orderNumber + " item is not eligible for return: " + reason);
        this.reason = reason;
    }

    public ReturnDenialReason getReason() {
        return reason;
    }
}