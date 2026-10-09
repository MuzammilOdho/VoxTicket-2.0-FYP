package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RecentOrderView(
        String orderNumber, OrderStatus orderStatus, FulfillmentStatus fulfillmentStatus, BigDecimal totalAmount, String currency, Instant placedAt) {
}