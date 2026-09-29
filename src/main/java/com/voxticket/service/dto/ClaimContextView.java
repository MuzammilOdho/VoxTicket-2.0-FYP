package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ClaimStatus;
import java.time.Instant;

/**
 * A claim record on the order. {@code itemName} is the customer-visible
 * product name the claim was filed against (not an internal identifier).
 * {@code status}, {@code reason}, and {@code requestedResolution} are stable
 * enum/code names. {@code supportTicketNumber}/{@code supportTicketStatus}
 * link the claim to its support ticket when one was created (ticket number
 * and status are customer-visible references; no internal UUIDs).
 *
 * <p>Nothing here promises a refund, replacement, approval, or timeframe.
 */
public record ClaimContextView(
        String claimNumber,
        ClaimStatus status,
        String reason,
        String requestedResolution,
        String itemName,
        Instant createdAt,
        String supportTicketNumber,
        String supportTicketStatus) {
}
