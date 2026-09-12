package com.voxticket.service.dto;

import java.time.Instant;

public record ReturnContextView(String returnNumber, String status, String reason, Instant requestedAt, Instant completedAt) {
}