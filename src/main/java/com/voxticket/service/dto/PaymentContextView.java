package com.voxticket.service.dto;

import java.math.BigDecimal;

public record PaymentContextView(String method, String status, BigDecimal amount, BigDecimal amountCollected, String currency) {
}