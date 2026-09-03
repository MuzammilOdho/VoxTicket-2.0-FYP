package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderSummaryView(
        String orderNumber,
        OrderStatus orderStatus,
        FulfillmentStatus fulfillmentStatus,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt,
        List<OrderItemView> items) {
}