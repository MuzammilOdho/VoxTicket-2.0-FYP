package com.voxticket.service.dto;

/**
 * Compact order-level rollup. The detailed cancellation capability lives once
 * on the context root ({@code OrderSupportContext.cancellation}); per-item
 * return/claim capabilities live on {@code items}; refund records live in
 * {@code refunds}. This rollup only answers "is anything actionable?" so the
 * model does not re-scan the item list - {@code anyClaimAvailable} is true
 * only when at least one item's claim state is actually {@code AVAILABLE}.
 */
public record SupportCapabilitiesView(
        boolean anyReturnAvailable,
        boolean anyClaimAvailable,
        RefundStateView refundState) {
}
