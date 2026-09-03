package com.voxticket.service.dto;

public record ReturnEligibilityView(String orderNumber, String sku, boolean eligible, String denialReason, int maxReturnableQuantity) {
}