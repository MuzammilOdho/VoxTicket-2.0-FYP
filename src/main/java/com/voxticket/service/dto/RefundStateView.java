package com.voxticket.service.dto;

/**
 * Descriptive refund state for one order. Refund is not a standalone
 * customer action in the current domain, so this carries no eligibility
 * flag - only facts: whether refund records exist, the latest refund's
 * status, and whether any refund failed or is pending. {@code latestStatus}
 * is a stable enum name or null when no refund exists. Failure causes are
 * deliberately absent: the domain persists no authoritative failure reason,
 * and inventing one would be fabrication.
 */
public record RefundStateView(boolean hasRefunds, String latestStatus, boolean hasFailedRefund, boolean hasPendingRefund) {
}
