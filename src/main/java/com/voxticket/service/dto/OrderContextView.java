package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Spec-aligned "complete order context" (Core Improvement #1). One call
 * aggregates everything an agent needs to answer naturally, instead of
 * requiring several round trips the model has to piece together itself.
 *
 * <p>This is the canonical model-facing aggregate: statuses and reasons are stable,
 * language-neutral enum/code names (serialized as the enum name), never English display
 * prose - the LLM performs presentation. cancellationEligibility is structured
 * ({@link CancellationEligibilityView}): the deterministic eligibility decision stays
 * machine-readable and the model phrases it for the customer itself. Items expose no
 * internal identifiers (no SKU, no database IDs); per-item return eligibility is
 * evaluated deterministically by {@code ReturnPolicyService}.
 */
public record OrderContextView(
        String orderNumber,
        OrderStatus orderStatus,
        FulfillmentStatus fulfillmentStatus,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt,
        List<OrderItemContextView> items,
        PaymentContextView payment,
        List<ShipmentContextView> shipments,
        CancellationEligibilityView cancellationEligibility,
        List<ReturnContextView> returns,
        List<RefundContextView> refunds,
        List<ClaimContextView> claims) {
}
