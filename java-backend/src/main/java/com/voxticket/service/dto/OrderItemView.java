package com.voxticket.service.dto;

import java.math.BigDecimal;

public record OrderItemView(String productName, String sku, int quantity, BigDecimal unitPrice, boolean returnable, boolean finalSale) {
}