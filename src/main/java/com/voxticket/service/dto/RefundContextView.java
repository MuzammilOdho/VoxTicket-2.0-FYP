package com.voxticket.service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundContextView(String refundNumber, String status, BigDecimal amount, Instant initiatedAt, Instant completedAt) {
}