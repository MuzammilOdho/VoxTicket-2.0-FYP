package com.voxticket.service.dto;

import java.math.BigDecimal;

/**
 * The model-facing order item. Internal identifiers (SKU, database IDs) are deliberately
 * absent: item resolution stays in Java, and per-item return eligibility is evaluated
 * deterministically by {@code ReturnPolicyService} and exposed via {@link #returnEligibility}.
 * The model must not derive eligibility from raw flags - none are exposed here.
 */
public record OrderItemContextView(
        String productName,
        int quantity,
        BigDecimal unitPrice,
        ItemReturnEligibilityView returnEligibility) {
}
