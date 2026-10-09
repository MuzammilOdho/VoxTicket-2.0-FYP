package com.voxticket.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.BaseEntity;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.ReturnItemRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Bulk return-policy evaluation (the aggregate order-read path) must be
 * semantically identical to the single-item path used by execution-time
 * rechecks, while issuing a bounded number of queries: one shipment lookup
 * per order and one return-item lookup per item set - never one per item.
 *
 * <p>Pure Mockito unit tests: no Spring, no database.
 */
class ReturnPolicyBulkEvaluationTest {

    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ReturnItemRepository returnItemRepository = mock(ReturnItemRepository.class);
    private final ReturnPolicyService policyService =
            new ReturnPolicyService(shipmentRepository, returnItemRepository, 30);

    /** Mirrors a persisted entity: internal IDs are always non-null in production. */
    private static void assignId(BaseEntity entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Order newOrder() {
        Customer customer = new Customer("Test", "User",
                "bulk." + UUID.randomUUID() + "@example.pk", "+923001234567");
        Order order = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                customer, "PKR", BigDecimal.valueOf(500), BigDecimal.ZERO, BigDecimal.valueOf(500), Instant.now());
        order.setOrderStatus(OrderStatus.OPEN);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        assignId(order, UUID.randomUUID());
        return order;
    }

    private OrderItem item(String name, int quantity, boolean returnable, boolean finalSale) {
        OrderItem item = new OrderItem(name, "SKU-" + UUID.randomUUID().toString().substring(0, 8),
                quantity, BigDecimal.valueOf(500), returnable, finalSale);
        assignId(item, UUID.randomUUID());
        return item;
    }

    private void stubDelivered(Order order, Instant deliveredAt) {
        Shipment shipment = new Shipment(order, "TCS", "TRACK-1", ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(deliveredAt);
        when(shipmentRepository.findByOrderId(order.getId())).thenReturn(List.of(shipment));
    }

    private ReturnItem committedReturn(OrderItem item, int quantity, ReturnStatus status) {
        ReturnRequest request = new ReturnRequest(
                "RET-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), item.getOrder(), ReturnReason.WRONG_SIZE);
        request.setStatus(status);
        ReturnItem returnItem = new ReturnItem(item, quantity, ReturnReason.WRONG_SIZE);
        request.addItem(returnItem);
        return returnItem;
    }

    // ---- Task 6: bulk results are exactly the single-item results ----

    @Test
    void bulkEvaluationMatchesSingleItemEvaluationAcrossAllRuleBranches() {
        Order order = newOrder();
        OrderItem inWindow = item("In Window", 2, true, false);
        OrderItem finalSale = item("Final Sale", 1, true, true);
        OrderItem notReturnable = item("Not Returnable", 1, false, false);
        OrderItem partiallyReturned = item("Partially Returned", 3, true, false);
        OrderItem fullyReturned = item("Fully Returned", 2, true, false);
        OrderItem rejectedReturn = item("Rejected Return", 2, true, false);
        List<OrderItem> items = List.of(
                inWindow, finalSale, notReturnable, partiallyReturned, fullyReturned, rejectedReturn);

        // One delivered shipment 10 days ago covers every item; the
        // ITEM_NOT_DELIVERED and RETURN_WINDOW_EXPIRED branches are pinned
        // in their own tests below because delivery state is order-level.
        stubDelivered(order, Instant.now().minus(10, ChronoUnit.DAYS));

        List<ReturnItem> returnItems = new ArrayList<>();
        returnItems.add(committedReturn(partiallyReturned, 1, ReturnStatus.REQUESTED));
        returnItems.add(committedReturn(fullyReturned, 2, ReturnStatus.COMPLETED));
        returnItems.add(committedReturn(rejectedReturn, 2, ReturnStatus.REJECTED));
        // The per-item stub must return only that item's returns, like production.
        when(returnItemRepository.findByOrderItemId(any())).thenAnswer(invocation -> {
            UUID queriedId = invocation.getArgument(0);
            return returnItems.stream()
                    .filter(ri -> queriedId.equals(ri.getOrderItem().getId()))
                    .toList();
        });
        when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(returnItems);

        // notDelivered sees a shipment list with no DELIVERED entry.
        Map<UUID, ReturnEligibility> bulk = policyService.evaluateAll(order, items);
        assertThat(bulk).hasSize(items.size());
        for (OrderItem item : items) {
            ReturnEligibility single = policyService.evaluate(order, item);
            assertThat(bulk.get(item.getId()))
                    .as("bulk vs single for %s", item.getProductName())
                    .isEqualTo(single);
        }

        // Pin the expected branch outcomes so a semantic drift is caught even
        // if both paths drift together.
        assertThat(bulk.get(inWindow.getId()).eligible()).isTrue();
        assertThat(bulk.get(inWindow.getId()).maxReturnableQuantity()).isEqualTo(2);
        assertThat(bulk.get(partiallyReturned.getId()).maxReturnableQuantity()).isEqualTo(2);
        assertThat(bulk.get(fullyReturned.getId()).denialReason()).isEqualTo(ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
        assertThat(bulk.get(rejectedReturn.getId()).eligible()).isTrue();
        assertThat(bulk.get(rejectedReturn.getId()).maxReturnableQuantity()).isEqualTo(2);
        assertThat(bulk.get(finalSale.getId()).denialReason()).isEqualTo(ReturnDenialReason.ITEM_FINAL_SALE);
        assertThat(bulk.get(notReturnable.getId()).denialReason()).isEqualTo(ReturnDenialReason.ITEM_NOT_RETURNABLE);
    }

    @Test
    void bulkEvaluationReportsItemNotDeliveredWhenNoDeliveredShipment() {
        Order order = newOrder();
        OrderItem item = item("Never Shipped", 1, true, false);
        when(shipmentRepository.findByOrderId(any())).thenReturn(List.of());
        when(returnItemRepository.findByOrderItemId(any())).thenReturn(List.of());
        when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(List.of());

        Map<UUID, ReturnEligibility> bulk = policyService.evaluateAll(order, List.of(item));

        assertThat(bulk.get(item.getId()).eligible()).isFalse();
        assertThat(bulk.get(item.getId()).denialReason()).isEqualTo(ReturnDenialReason.ITEM_NOT_DELIVERED);
        assertThat(bulk.get(item.getId())).isEqualTo(policyService.evaluate(order, item));
    }

    @Test
    void bulkEvaluationReportsWindowExpiredForOldDelivery() {
        Order order = newOrder();
        OrderItem item = item("Old Delivery", 1, true, false);
        stubDelivered(order, Instant.now().minus(31, ChronoUnit.DAYS));
        when(returnItemRepository.findByOrderItemId(any())).thenReturn(List.of());
        when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(List.of());

        Map<UUID, ReturnEligibility> bulk = policyService.evaluateAll(order, List.of(item));

        assertThat(bulk.get(item.getId()).denialReason()).isEqualTo(ReturnDenialReason.RETURN_WINDOW_EXPIRED);
        assertThat(bulk.get(item.getId())).isEqualTo(policyService.evaluate(order, item));
    }

    // ---- Task 5: query shape - no N+1 ----

    @Test
    void bulkEvaluationIssuesOneShipmentQueryForMultiItemOrder() {
        Order order = newOrder();
        List<OrderItem> items = List.of(
                item("A", 1, true, false), item("B", 1, true, false), item("C", 1, true, false),
                item("D", 1, true, false), item("E", 1, true, false));
        stubDelivered(order, Instant.now());
        when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(List.of());

        policyService.evaluateAll(order, items);

        verify(shipmentRepository, times(1)).findByOrderId(any());
    }

    @Test
    void bulkEvaluationNeverIssuesPerItemReturnItemQueries() {
        Order order = newOrder();
        List<OrderItem> items = List.of(
                item("A", 1, true, false), item("B", 1, true, false), item("C", 1, true, false));
        stubDelivered(order, Instant.now());
        when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(List.of());

        policyService.evaluateAll(order, items);

        verify(returnItemRepository, never()).findByOrderItemId(any());
        verify(returnItemRepository, times(1))
                .findByOrderItemIdIn(argThat(ids -> ids != null && ids.size() == 3));
    }
}
