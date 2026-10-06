package com.voxticket.service.dto;

/**
 * Deterministic order-level cancellation capability, evaluated by
 * {@code CancellationPolicyService}. {@code denialReason} is a stable
 * enum/code name (e.g. {@code ORDER_FULFILLED}, {@code ALREADY_CANCELLED}) or
 * null when available; {@code paymentConsequence} is the stable consequence
 * code (e.g. {@code REFUND_REQUIRED}, {@code NO_REFUND_REQUIRED}) or null -
 * never English prose, so the model presents them rather than deriving them.
 */
public record CancellationCapabilityView(boolean available, String denialReason, String paymentConsequence) {
}
