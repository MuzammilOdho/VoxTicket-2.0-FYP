package com.voxticket.service.dto;

public record CancellationEligibilityView(String orderNumber, boolean eligible, String denialReason, String paymentConsequence) {
}