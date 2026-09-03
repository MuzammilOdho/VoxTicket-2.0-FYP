package com.voxticket.identity;

import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.repository.OrderItemRepository;
import org.springframework.stereotype.Component;

/**
 * Resolves an item reference (SKU) to an {@link OrderItem} within an
 * already-verified order. This is the item-level counterpart to
 * {@link OwnedOrderResolver}: it exists so a return/claim request can only
 * ever act on an item that is actually part of the order the caller already
 * proved ownership of, using the same customer-safe reference (a SKU) that
 * the rest of the system exposes - never an internal UUID.
 */
@Component
public class OwnedOrderItemResolver {

    private final OrderItemRepository orderItemRepository;

    public OwnedOrderItemResolver(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    public OrderItem resolveBySku(VerifiedOrderRef orderRef, String sku) {
        return orderItemRepository.findByOrderId(orderRef.orderId()).stream()
                .filter(item -> item.getSku().equalsIgnoreCase(sku))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundForAccountException("ORDER_ITEM", sku));
    }
}