package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ReturnStatus;
import java.time.Instant;

public record ReturnStatusView(String returnNumber, String orderNumber, ReturnStatus status, String reason, Instant requestedAt, Instant completedAt) {
}