package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The single authoritative model-facing support-state read for one order,
 * returned by {@code getMyOrderContext}. Capability fields (cancellation,
 * per-item return and claim, refund state) are computed deterministically in
 * Java from domain policy - the model must not infer what actions are
 * available from the raw order/shipment/payment fields alone.
 *
 * <p>All fields are stable, language-neutral codes and values: statuses are
 * serialized as enum names, denial reasons are stable enum/code names (never
 * English prose - the model phrases them for the customer). No internal
 * identifiers are exposed: no UUIDs, no customer IDs, no internal order IDs,
 * no procedure or challenge IDs, no OTP material, no SKUs.
 *
 * <p>{@link #refunds} is descriptive only: refund records, statuses, amounts,
 * and failure state. Refund is not a standalone customer action in the
 * current domain, so no refund-eligibility flag is invented. A failed refund
 * exposes {@code failedAt} but no failure cause - the domain persists no
 * authoritative failure reason.
 */
public record OrderSupportContext(
        String orderNumber,
        OrderStatus orderStatus,
        FulfillmentStatus fulfillmentStatus,
        Instant placedAt,
        String currency,
        BigDecimal totalAmount,
        List<OrderSupportItemView> items,
        PaymentContextView payment,
        List<ShipmentContextView> shipments,
        CancellationCapabilityView cancellation,
        List<ReturnContextView> returns,
        List<RefundContextView> refunds,
        List<ClaimContextView> claims,
        SupportCapabilitiesView supportCapabilities) {
}
