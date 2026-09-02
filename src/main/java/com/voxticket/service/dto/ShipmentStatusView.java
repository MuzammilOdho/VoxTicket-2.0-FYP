package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.ShipmentStatus;
import java.time.Instant;

public record ShipmentStatusView(
        String orderNumber, String carrier, String trackingNumber, ShipmentStatus status, Instant estimatedDeliveryAt, Instant deliveredAt) {
}