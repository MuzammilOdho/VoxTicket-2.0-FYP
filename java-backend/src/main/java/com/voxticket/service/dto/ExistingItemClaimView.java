package com.voxticket.service.dto;

/**
 * The open or in-review claim already recorded against one order item, made
 * visible inside that item's claim capability so the model does not suggest
 * filing another identical claim. {@code claimNumber} is the customer-visible
 * claim reference (not an internal ID); {@code status} and {@code reason} are
 * stable enum/code names.
 */
public record ExistingItemClaimView(String claimNumber, String status, String reason) {
}
