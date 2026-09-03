package com.voxticket.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CancellationPolicyServiceTest {

    private final CancellationPolicyService policyService = new CancellationPolicyService();
    private final Customer customer = new Customer("Test", "User", "test@example.pk", "+923000000000");

    private Order newOrder() {
        return new Order("ORD-TEST", customer, "PKR", BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000), Instant.now());
    }

    @Test
    void codUnfulfilledIsEligibleWithNoRefundRequired() {
        Order order = newOrder();
        Payment payment = new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isTrue();
        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.NO_REFUND_REQUIRED);
    }

    @Test
    void cardPaidUnfulfilledIsEligibleWithRefundRequired() {
        Order order = newOrder();
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isTrue();
        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.REFUND_REQUIRED);
    }

    @Test
    void authorizedNotCapturedIsEligibleWithVoidRequired() {
        Order order = newOrder();
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.AUTHORIZED);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isTrue();
        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.VOID_AUTHORIZATION);
    }

    @Test
    void nonCodPendingPaymentRequiresManualReview() {
        Order order = newOrder();
        Payment payment = new Payment(order, PaymentMethod.BANK_TRANSFER, order.getTotalAmount(), "PKR", PaymentStatus.PENDING);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isTrue();
        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void alreadyCancelledOrderIsDenied() {
        Order order = newOrder();
        order.setOrderStatus(OrderStatus.CANCELLED);
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.REFUNDED);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(CancellationDenialReason.ALREADY_CANCELLED);
    }

    @Test
    void completedOrderIsDenied() {
        Order order = newOrder();
        order.setOrderStatus(OrderStatus.COMPLETED);
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(CancellationDenialReason.ORDER_ALREADY_COMPLETED);
    }

    @Test
    void fulfilledOrderIsDeniedEvenIfStillOpen() {
        Order order = newOrder();
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(CancellationDenialReason.ORDER_FULFILLED);
    }

    @Test
    void partiallyFulfilledOrderIsDenied() {
        Order order = newOrder();
        order.setFulfillmentStatus(FulfillmentStatus.PARTIALLY_FULFILLED);
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(CancellationDenialReason.ORDER_FULFILLED);
    }

    @Test
    void alreadyVoidedPaymentIsIncompatible() {
        Order order = newOrder();
        Payment payment = new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.VOIDED);

        var result = policyService.evaluate(order, payment);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(CancellationDenialReason.PAYMENT_STATE_INCOMPATIBLE);
    }
}