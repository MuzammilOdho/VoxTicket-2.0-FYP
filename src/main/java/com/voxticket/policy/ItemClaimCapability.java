package com.voxticket.policy;

import com.voxticket.persistence.entity.enums.ClaimResolution;

/**
 * Per-item claim capability as computed by {@link ClaimPolicyService}.
 * Pure value - no repository access, trivially unit-testable.
 *
 * <p>{@code resolution} is the resolution the current product actually uses
 * when a claim is filed (manual review); it is informational and never a
 * promise of refund, replacement, approval, or timeframe.
 */
public record ItemClaimCapability(
        ClaimCapabilityState state, ClaimDenialReason denialReason, ClaimResolution resolution) {

    public static ItemClaimCapability available(ClaimResolution resolution) {
        return new ItemClaimCapability(ClaimCapabilityState.AVAILABLE, null, resolution);
    }

    public static ItemClaimCapability existingActive() {
        return new ItemClaimCapability(ClaimCapabilityState.EXISTING_ACTIVE, null, null);
    }

    public static ItemClaimCapability unavailable(ClaimDenialReason reason) {
        return new ItemClaimCapability(ClaimCapabilityState.UNAVAILABLE, reason, null);
    }
}
