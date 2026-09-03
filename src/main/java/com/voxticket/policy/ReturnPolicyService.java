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
import java.util.Comparator;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public ReturnEligibility evaluate(Order order, OrderItem item) {
        Instant deliveredAt = latestDeliveryDate(order);
        if (deliveredAt == null) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_NOT_DELIVERED);
        }
        if (deliveredAt.plus(returnWindowDays, ChronoUnit.DAYS).isBefore(Instant.now())) {
            return ReturnEligibility.denied(ReturnDenialReason.RETURN_WINDOW_EXPIRED);
        }
        // Final sale overrides everything else, even if returnable happens to also be true.
        if (item.isFinalSale()) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_FINAL_SALE);
        }
        if (!item.isReturnable()) {
            return ReturnEligibility.denied(ReturnDenialReason.ITEM_NOT_RETURNABLE);
        }

        int remaining = item.getQuantity() - alreadyCommittedQuantity(item);
        if (remaining <= 0) {
            return ReturnEligibility.denied(ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
        }
        return ReturnEligibility.eligible(remaining);
    }

    private Instant latestDeliveryDate(Order order) {
        return shipmentRepository.findByOrderId(order.getId()).stream()
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
}