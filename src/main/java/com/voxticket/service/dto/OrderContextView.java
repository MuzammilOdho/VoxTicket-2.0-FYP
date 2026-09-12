package com.voxticket.service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Spec-aligned "complete order context" (Core Improvement #1). One call
 * aggregates everything an agent needs to answer naturally, instead of
 * requiring several round trips the model has to piece together itself.
 * cancellationEligibility is the one field genuinely requiring business
 * logic to derive (spec §37/§38) - everything else is just translated,
 * aggregated fact.
 */
public record OrderContextView(
        String orderNumber,
        String orderStatus,
        String fulfillmentStatus,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt,
        List<OrderItemView> items,
        PaymentContextView payment,
        List<ShipmentContextView> shipments,
        String cancellationEligibility,
        List<ReturnContextView> returns,
        List<RefundContextView> refunds,
        List<ClaimContextView> claims) {
}