package com.voxticket.policy;

/**
 * Model-facing claim capability for one order item. Explicit states replace
 * the ambiguous {@code available=true + existingClaim=...} combination, which
 * could read as a new-claim opportunity on an item that already has one.
 *
 * <ul>
 *   <li>{@code AVAILABLE} - a new claim may be filed (resolution is {@code MANUAL_REVIEW}).</li>
 *   <li>{@code EXISTING_ACTIVE} - an OPEN/IN_REVIEW claim already exists for this item;
 *       it is visible via {@code existingClaim}; no new claim is implied.</li>
 *   <li>{@code UNAVAILABLE} - the domain forbids filing ({@code denialReason} says why).</li>
 * </ul>
 *
 * <p>Read-side capability semantics only: the domain adds no duplicate-claim
 * constraint in this pass.
 */
public enum ClaimCapabilityState {
    AVAILABLE,
    EXISTING_ACTIVE,
    UNAVAILABLE
}
