package com.voxticket.service.dto;

/**
 * Deterministic per-item return capability, evaluated by
 * {@code ReturnPolicyService}. {@code denialReason} is a stable enum/code
 * name (e.g. {@code ITEM_FINAL_SALE}, {@code RETURN_WINDOW_EXPIRED}) or null
 * when available - never English prose, so the model presents it rather
 * than deriving it. {@code maxReturnableQuantity} already accounts for
 * quantity tied up in previous non-rejected returns.
 */
public record ItemReturnCapabilityView(boolean available, String denialReason, int maxReturnableQuantity) {
}
