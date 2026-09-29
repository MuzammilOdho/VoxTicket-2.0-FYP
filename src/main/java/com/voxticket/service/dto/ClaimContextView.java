package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ClaimStatus;

public record ClaimContextView(String claimNumber, ClaimStatus status, String reason, String requestedResolution) {
}
