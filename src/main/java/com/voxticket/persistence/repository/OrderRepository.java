package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.Order;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    /** The IDOR-safe lookup (spec §10): ownership is enforced in the SQL WHERE clause, not by filtering in Java afterward. */
    Optional<Order> findByOrderNumberAndCustomerId(String orderNumber, UUID customerId);

    List<Order> findByCustomerIdOrderByPlacedAtDesc(UUID customerId);

    /** Pageable overload used by getRecentOrders(identity, limit) to apply the limit at the DB level. */
    List<Order> findByCustomerIdOrderByPlacedAtDesc(UUID customerId, Pageable pageable);

    /**
     * Row-locked order fetch for financial mutations (cancellation, return).
     * The {@code PESSIMISTIC_WRITE} lock serializes concurrent mutations of the
     * same order across sessions and application instances: without it, two
     * simultaneous requests could both pass the eligibility check and create
     * duplicate refunds/returns. Callers must already run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
