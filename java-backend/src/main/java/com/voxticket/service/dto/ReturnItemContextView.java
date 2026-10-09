package com.voxticket.service.dto;

/**
 * One returned line item. {@code productName} is customer-visible; {@code quantity}
 * is the number of units returned; {@code reason} is the persisted per-item
 * return reason (stable code name); {@code condition} is the inspection
 * condition (stable code name) or null when the item has not been inspected
 * yet. No internal IDs or SKUs.
 */
public record ReturnItemContextView(String productName, int quantity, String reason, String condition) {
}
