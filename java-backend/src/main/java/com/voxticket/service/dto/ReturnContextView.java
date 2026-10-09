package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ReturnStatus;
import java.time.Instant;
import java.util.List;

/**
 * A return record on the order. Lifecycle timestamps are exposed so the
 * model can tell where the return is in its lifecycle (requested, approved,
 * received, inspected, completed); {@code inspectedAt} being non-null means
 * inspection happened. {@code items} carries the structured returned line
 * items with persisted per-item quantity/reason/condition, so the model can
 * answer how many units of which item were returned without guessing.
 *
 * <p>No internal IDs or SKUs are exposed.
 */
public record ReturnContextView(
        String returnNumber,
        ReturnStatus status,
        String reason,
        Instant requestedAt,
        Instant approvedAt,
        Instant receivedAt,
        Instant inspectedAt,
        Instant completedAt,
        List<ReturnItemContextView> items) {
}
