package com.voxticket.policy;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.ReturnItemRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §37. Unlike {@link CancellationPolicyService}, this one does read
 * from repositories - delivery date and already-returned quantity are
 * cross-entity facts, not attributes of the OrderItem itself.
 *
 * <p>Delivery is inferred from the order's shipments as a whole (this schema
 * doesn't map individual shipments to individual items - spec's Shipment
 * entity is order-scoped, spec §29). For an order with multiple shipments,
 * the most recent DELIVERED one is used. Per-item shipment tracking is out
 * of scope for the FYP; flagging this as a modeling simplification rather
 * than silently assuming it away.
 */
@Service
public class ReturnPolicyService {

    private static final Set<ReturnStatus> NON_CONSUMING_STATUSES = Set.of(ReturnStatus.REJECTED, ReturnStatus.CANCELLED);

    private final ShipmentRepository shipmentRepository;
    private final ReturnItemRepository returnItemRepository;
    private final int returnWindowDays;

    public ReturnPolicyService(
            ShipmentRepository shipmentRepository,
            ReturnItemRepository returnItemRepository,
            @Value("${voxticket.commerce.returns.window-days:30}") int returnWindowDays) {
        this.shipmentRepository = shipmentRepository;
        this.returnItemRepository = returnItemRepository;
        this.returnWindowDays = returnWindowDays;
    }

    /**
     * Runs in its own read-only transaction: callers such as
     * {@code ProcedureCoordinator.startReturn} are not transactional, and this
     * method dereferences lazy {@code ReturnItem -> ReturnRequest} proxies.
     * Without the transaction boundary that access throws
     * {@code LazyInitializationException} ("Could not initialize proxy ... -
     * no session"), which used to surface to the model as a non-JSON tool
     * error and crash the native Google provider's request building.
     */
    @Transactional(readOnly = true)
    public ReturnEligibility evaluate(Order order, OrderItem item) {
        return decide(item, latestDeliveryDate(shipmentRepository.findByOrderId(order.getId())), alreadyCommittedQuantity(item), Instant.now());
    }

    /**
     * Bulk read-path evaluation for aggregate order views: loads delivery
     * state once per order and committed return quantities once per item
     * set, then computes every per-item {@link ReturnEligibility} in memory.
     * The returned map is keyed by the internal {@link OrderItem} ID and must
     * not be exposed model-side. Semantics are exactly those of
     * {@link #evaluate(Order, OrderItem)} - see {@link #decide}; the two are
     * pinned equal by test.
     *
     * <p>Execution-time callers (returns procedure, ReturnService) keep using
     * the single-item {@code evaluate} for their authoritative rechecks.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ReturnEligibility> evaluateAll(Order order, List<OrderItem> items) {
        return evaluateAll(order, items, shipmentRepository.findByOrderId(order.getId()));
    }

    /**
     * Same as {@link #evaluateAll(Order, List)} but takes an already-loaded
     * shipment list, so aggregate readers that fetched shipments for their
     * own history section (e.g. {@code CustomerOrderQueryService}) do not pay
     * for the same shipment query twice. Semantics are identical - the
     * shipment list is only consulted for the latest DELIVERED date.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ReturnEligibility> evaluateAll(Order order, List<OrderItem> items, List<Shipment> shipments) {
        Instant deliveredAt = latestDeliveryDate(shipments);
        Map<UUID, Integer> committedByItem = bulkCommittedQuantities(itemIds(items));
        Instant now = Instant.now();
        Map<UUID, ReturnEligibility> result = new LinkedHashMap<>();
        for (OrderItem item : items) {
            result.put(item.getId(), decide(item, deliveredAt, committedByItem.getOrDefault(item.getId(), 0), now));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * The exact per-item decision shared by both the single-item and bulk
     * paths. Rule order is deliberate and unchanged: delivery and window
     * first, then final-sale and returnability, then remaining quantity.
     */
    private ReturnEligibility decide(OrderItem item, Instant deliveredAt, int alreadyCommitted, Instant now) {
        if (deliveredAt == null) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_NOT_DELIVERED);
        }
        if (deliveredAt.plus(returnWindowDays, ChronoUnit.DAYS).isBefore(now)) {
            return ReturnEligibility.denied(ReturnDenialReason.RETURN_WINDOW_EXPIRED);
        }
        // Final sale overrides everything else, even if returnable happens to also be true.
        if (item.isFinalSale()) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_FINAL_SALE);
        }
        if (!item.isReturnable()) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_NOT_RETURNABLE);
        }

        int remaining = item.getQuantity() - alreadyCommitted;
        if (remaining <= 0) {
            return ReturnEligibility.denied(ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
        }
        return ReturnEligibility.eligible(remaining);
    }

    private Instant latestDeliveryDate(List<Shipment> shipments) {
        return shipments.stream()
                .filter(s -> s.getStatus() == ShipmentStatus.DELIVERED && s.getDeliveredAt() != null)
                .map(Shipment::getDeliveredAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Quantity already tied up in a return that hasn't been rejected/cancelled - REQUESTED through COMPLETED all count. */
    private int alreadyCommittedQuantity(OrderItem item) {
        return returnItemRepository.findByOrderItemId(item.getId()).stream()
                .filter(ri -> !NON_CONSUMING_STATUSES.contains(ri.getReturnRequest().getStatus()))
                .mapToInt(ReturnItem::getQuantity)
                .sum();
    }

    /**
     * Same committed-quantity accounting as {@link #alreadyCommittedQuantity},
     * but loaded with one query for the whole item set instead of one per
     * item. Transient items (null IDs, used in unit tests) are skipped from
     * the lookup and default to zero committed.
     */
    private Map<UUID, Integer> bulkCommittedQuantities(List<UUID> orderItemIds) {
        List<UUID> persistedIds = orderItemIds.stream().filter(Objects::nonNull).toList();
        if (persistedIds.isEmpty()) {
            return Map.of();
        }
        return returnItemRepository.findByOrderItemIdIn(persistedIds).stream()
                .filter(ri -> !NON_CONSUMING_STATUSES.contains(ri.getReturnRequest().getStatus()))
                .collect(Collectors.groupingBy(
                        ri -> ri.getOrderItem().getId(), Collectors.summingInt(ReturnItem::getQuantity)));
    }

    private List<UUID> itemIds(List<OrderItem> items) {
        List<UUID> ids = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            ids.add(item.getId());
        }
        return ids;
    }
}
