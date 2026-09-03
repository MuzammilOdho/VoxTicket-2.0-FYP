package com.voxticket.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.ReturnItemRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReturnPolicyServiceTest {

    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ReturnItemRepository returnItemRepository = mock(ReturnItemRepository.class);
    private final ReturnPolicyService returnPolicyService = new ReturnPolicyService(shipmentRepository, returnItemRepository, 30);

    private Order order;
    private OrderItem item;

    @BeforeEach
    void setUp() {
        Customer customer = new Customer("Test", "User", "test@example.pk", "+923000000000");
        order = new Order("ORD-TEST", customer, "PKR", BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000), Instant.now());
        item = new OrderItem("Test Item", "SKU-1", 2, BigDecimal.valueOf(500), true, false);
        order.addItem(item);
        when(returnItemRepository.findByOrderItemId(any())).thenReturn(List.of());
    }

    private void deliveredDaysAgo(long days) {
        Shipment shipment = new Shipment(order, "TCS", "TCS-1", ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(Instant.now().minus(days, ChronoUnit.DAYS));
        when(shipmentRepository.findByOrderId(any())).thenReturn(List.of(shipment));
    }

    @Test
    void notDeliveredIsIneligible() {
        when(shipmentRepository.findByOrderId(any())).thenReturn(List.of());

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(ReturnDenialReason.ITEM_NOT_DELIVERED);
    }

    @Test
    void deliveredWithinWindowIsEligibleForFullQuantity() {
        deliveredDaysAgo(10);

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isTrue();
        assertThat(result.maxReturnableQuantity()).isEqualTo(2);
    }

    @Test
    void deliveredOutsideWindowIsIneligible() {
        deliveredDaysAgo(45);

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(ReturnDenialReason.RETURN_WINDOW_EXPIRED);
    }

    @Test
    void finalSaleItemIsIneligibleEvenWhenReturnableFlagIsTrue() {
        deliveredDaysAgo(5);
        item.setFinalSale(true);

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(ReturnDenialReason.ITEM_FINAL_SALE);
    }

    @Test
    void nonReturnableItemIsIneligible() {
        deliveredDaysAgo(5);
        item.setReturnable(false);

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(ReturnDenialReason.ITEM_NOT_RETURNABLE);
    }

    @Test
    void alreadyFullyReturnedQuantityIsIneligible() {
        deliveredDaysAgo(5);
        ReturnRequest priorReturn = new ReturnRequest("RTN-PRIOR", order, ReturnReason.OTHER);
        priorReturn.setStatus(ReturnStatus.COMPLETED);
        ReturnItem priorReturnItem = new ReturnItem(item, 2, ReturnReason.OTHER);
        priorReturn.addItem(priorReturnItem);
        when(returnItemRepository.findByOrderItemId(any())).thenReturn(List.of(priorReturnItem));

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isFalse();
        assertThat(result.denialReason()).isEqualTo(ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
    }

    @Test
    void rejectedPriorReturnDoesNotConsumeQuantity() {
        deliveredDaysAgo(5);
        ReturnRequest priorReturn = new ReturnRequest("RTN-PRIOR", order, ReturnReason.OTHER);
        priorReturn.setStatus(ReturnStatus.REJECTED);
        ReturnItem priorReturnItem = new ReturnItem(item, 2, ReturnReason.OTHER);
        priorReturn.addItem(priorReturnItem);
        when(returnItemRepository.findByOrderItemId(any())).thenReturn(List.of(priorReturnItem));

        var result = returnPolicyService.evaluate(order, item);

        assertThat(result.eligible()).isTrue();
        assertThat(result.maxReturnableQuantity()).isEqualTo(2);
    }
}