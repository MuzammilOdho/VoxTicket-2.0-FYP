package com.voxticket.policy;

import com.voxticket.persistence.entity.enums.ClaimResolution;

/**
 * Deterministic claim-filing eligibility. Pure value - no repository access,
 * trivially unit-testable without a database or Spring context. {@code resolution}
 * is the resolution the current product actually uses when a claim is filed
 * (manual review); it is informational and never a promise of refund,
 * replacement, approval, or timeframe.
 */
public record ClaimEligibility(boolean eligible, ClaimDenialReason denialReason, ClaimResolution resolution) {

    public static ClaimEligibility eligible(ClaimResolution resolution) {
        return new ClaimEligibility(true, null, resolution);
    }

    public static ClaimEligibility denied(ClaimDenialReason reason) {
        return new ClaimEligibility(false, reason, null);
    }
}
