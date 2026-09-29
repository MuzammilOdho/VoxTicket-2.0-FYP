package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A failed refund exposes {@link #failedAt} so the model can tell "FAILED at a known time" apart
 * from a refund that never failed. There is deliberately no failure-cause field: the domain
 * persists no authoritative failure reason, and inventing one (provider name, error text) would
 * be fabrication. A FAILED refund with no recorded cause is therefore "cause unavailable".
 */
public record RefundContextView(String refundNumber, RefundStatus status, BigDecimal amount, Instant initiatedAt, Instant completedAt, Instant failedAt) {
}
