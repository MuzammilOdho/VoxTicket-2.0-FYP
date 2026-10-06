package com.voxticket.policy;

/** The single deterministic reason claim filing can be denied in the current product. */
public enum ClaimDenialReason {
    /** The domain forbids claims against cancelled orders (enforced in ClaimService.fileClaim). */
    ORDER_CANCELLED
}
