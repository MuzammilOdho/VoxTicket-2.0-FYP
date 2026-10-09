package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund record on the order. {@code reason} is the persisted refund reason
 * (stable code name, e.g. {@code CANCELLATION}, {@code RETURN}). {@code failedAt}
 * exposes when a refund failed; there is deliberately no failure-cause field -
 * the domain persists no authoritative failure reason, and inventing one
 * (provider name, error text) would be fabrication. {@code relatedReturnNumber}
 * links the refund to its originating return when one exists; a
 * cancellation-triggered refund has none.
 *
 * <p>Never exposes {@code providerReference}, payment/entity IDs, or
 * internal UUIDs.
 */
public record RefundContextView(
        String refundNumber,
        RefundStatus status,
        BigDecimal amount,
        String reason,
        Instant initiatedAt,
        Instant completedAt,
        Instant failedAt,
        String relatedReturnNumber) {
}
