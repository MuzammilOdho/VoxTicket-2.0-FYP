package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import java.math.BigDecimal;

public record PaymentContextView(PaymentMethod method, PaymentStatus status, BigDecimal amount, BigDecimal amountCollected, String currency) {
}
