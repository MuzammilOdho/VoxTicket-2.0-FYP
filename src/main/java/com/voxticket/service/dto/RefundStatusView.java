package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RefundStatusView(
        String refundNumber,
        String orderNumber,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        Instant initiatedAt,
        Instant completedAt,
        Instant failedAt) {
}