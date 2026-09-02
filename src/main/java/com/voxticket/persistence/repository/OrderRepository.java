package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByOrderNumber(String orderNumber);

    /** The IDOR-safe lookup (spec §10): ownership is enforced in the SQL WHERE clause, not by filtering in Java afterward. */
    Optional<Order> findByOrderNumberAndCustomerId(String orderNumber, UUID customerId);

    List<Order> findByCustomerIdOrderByPlacedAtDesc(UUID customerId);

    /** Pageable overload used by getRecentOrders(identity, limit) to apply the limit at the DB level. */
    List<Order> findByCustomerIdOrderByPlacedAtDesc(UUID customerId, Pageable pageable);
}