package com.voxticket.identity;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.repository.OrderRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The single choke point for turning a customer-supplied order reference
 * into something the rest of the system may trust. Every customer-scoped
 * query (this phase) and every procedure that later acts on "the order the
 * customer is talking about" (Phase 3/8) must go through here rather than
 * querying {@link OrderRepository} by order number directly.
 *
 * <p>The ownership check happens in the SQL WHERE clause
 * ({@link OrderRepository#findByOrderNumberAndCustomerId}), not by fetching
 * the order and then filtering in Java - this is the concrete version of the
 * "bad vs. better" example in spec §10.
 */
@Component
public class OwnedOrderResolver {

    private final OrderRepository orderRepository;

    public OwnedOrderResolver(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public VerifiedOrderRef resolve(CustomerIdentity identity, String orderNumber) {
        var customerId = identity.requireCustomerId();
        Order order = orderRepository.findByOrderNumberAndCustomerId(orderNumber, customerId)
                .orElseThrow(() -> new ResourceNotFoundForAccountException("ORDER", orderNumber));
        return new VerifiedOrderRef(order.getId(), order.getOrderNumber(), customerId, identity.assuranceLevel(), Instant.now());
    }
}