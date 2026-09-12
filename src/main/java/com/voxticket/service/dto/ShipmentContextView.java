package com.voxticket.service.dto;

import java.time.Instant;

public record ShipmentContextView(String carrier, String trackingNumber, String status, Instant estimatedDeliveryAt, Instant deliveredAt) {
}