package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OrderClaim;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, UUID> {
    Optional<OrderClaim> findByClaimNumber(String claimNumber);
    List<OrderClaim> findByOrderId(UUID orderId);

    /**
     * Row-locked claim fetch for refund resolution.
     * The {@code PESSIMISTIC_WRITE} lock serializes concurrent resolutions of
     * the same claim: without it, two simultaneous resolutions could both pass
     * the state check and create duplicate refunds. Callers must already run
     * inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OrderClaim c where c.id = :id")
    Optional<OrderClaim> findByIdForUpdate(@Param("id") UUID id);
}
