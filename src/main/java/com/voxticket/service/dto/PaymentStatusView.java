package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import java.math.BigDecimal;

public record PaymentStatusView(String orderNumber, PaymentMethod method, PaymentStatus status, BigDecimal amount, String currency) {
}