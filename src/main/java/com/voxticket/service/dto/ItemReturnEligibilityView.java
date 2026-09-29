package com.voxticket.service.dto;

/**
 * Deterministic per-item return eligibility, evaluated by {@code ReturnPolicyService}.
 * {@code denialReason} is a stable enum/code name (e.g. {@code ITEM_FINAL_SALE},
 * {@code RETURN_WINDOW_EXPIRED}) or null when eligible - never English prose, so the
 * model presents it rather than deriving it.
 */
public record ItemReturnEligibilityView(boolean eligible, String denialReason, int maxReturnableQuantity) {
}
