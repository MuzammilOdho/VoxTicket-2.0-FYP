package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ReturnStatus;
import java.time.Instant;

public record ReturnContextView(String returnNumber, ReturnStatus status, String reason, Instant requestedAt, Instant completedAt) {
}
