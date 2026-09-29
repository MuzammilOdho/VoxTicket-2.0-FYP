package com.voxticket.service.dto;

import java.math.BigDecimal;

/**
 * The model-facing order item. Internal identifiers (SKU, database IDs) are
 * deliberately absent: item resolution stays in Java. Return and claim
 * capability are evaluated deterministically by the policy services and
 * exposed here - the model must not derive them from raw flags, because none
 * are exposed. Each item's capability is computed independently, so different
 * items on the same order may differ.
 */
public record OrderSupportItemView(
        String productName,
        int quantity,
        BigDecimal unitPrice,
        ItemReturnCapabilityView returnCapability,
        ItemClaimCapabilityView claimCapability) {
}
