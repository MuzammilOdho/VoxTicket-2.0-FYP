package com.voxticket.service.dto;

import com.voxticket.policy.ClaimCapabilityState;

/**
 * Deterministic per-item claim capability, evaluated by
 * {@code ClaimPolicyService}. {@code state} is explicit - never an ambiguous
 * "available plus an existing claim" combination:
 *
 * <ul>
 *   <li>{@code AVAILABLE} - a new claim may be filed; {@code resolution} is
 *       the resolution the current product actually uses
 *       ({@code MANUAL_REVIEW}), informational and never a promise of refund,
 *       replacement, approval, or timeframe.</li>
 *   <li>{@code EXISTING_ACTIVE} - an OPEN/IN_REVIEW claim already exists on
 *       this item (see {@code existingClaim}); no new claim is implied.</li>
 *   <li>{@code UNAVAILABLE} - filing is forbidden; {@code denialReason} is a
 *       stable code (e.g. {@code ORDER_CANCELLED}), never English prose.</li>
 * </ul>
 */
public record ItemClaimCapabilityView(
        ClaimCapabilityState state,
        String denialReason,
        String resolution,
        ExistingItemClaimView existingClaim) {
}
