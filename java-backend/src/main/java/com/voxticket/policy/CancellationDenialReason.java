package com.voxticket.policy;

public enum CancellationDenialReason {
    ALREADY_CANCELLED,
    ORDER_ALREADY_COMPLETED,
    ORDER_FULFILLED,
    PAYMENT_STATE_INCOMPATIBLE
}