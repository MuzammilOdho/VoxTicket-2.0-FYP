package com.voxticket.service.dto;

public record ClaimContextView(String claimNumber, String status, String reason, String requestedResolution) {
}