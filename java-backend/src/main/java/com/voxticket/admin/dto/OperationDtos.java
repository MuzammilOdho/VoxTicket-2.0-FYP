package com.voxticket.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phase 12 (P4): read-only operations DTOs. Flat projections of commerce
 * entities for the admin Operations pages. No business logic, no writes.
 * The verification challenge DTO deliberately excludes the OTP hash/salt.
 */
public final class OperationDtos {

    private OperationDtos() {
    }

    public record OrderDto(
            String orderNumber,
            String status,
            String fulfillmentStatus,
            String currency,
            BigDecimal totalAmount,
            Instant placedAt,
            int itemCount) {
    }

    public record ReturnDto(
            String returnNumber,
            String orderNumber,
            String status,
            String reason,
            Instant requestedAt,
            Instant completedAt) {
    }

    public record RefundDto(
            String refundNumber,
            String orderNumber,
            BigDecimal amount,
            String status,
            String reason,
            Instant initiatedAt,
            Instant completedAt) {
    }

    public record ClaimDto(
            String claimNumber,
            String orderNumber,
            String reason,
            String requestedResolution,
            String status,
            Instant createdAt) {
    }

    public record VerificationChallengeDto(
            String id,
            String sessionId,
            String purpose,
            String deliveryChannel,
            String maskedDestination,
            int attempts,
            boolean consumed,
            boolean verified,
            boolean expired,
            Instant createdAt,
            Instant expiresAt) {
    }

    public record EscalationDto(
            String ticketNumber,
            String category,
            String priority,
            String status,
            String summary,
            Instant createdAt,
            Instant resolvedAt) {
    }
}
