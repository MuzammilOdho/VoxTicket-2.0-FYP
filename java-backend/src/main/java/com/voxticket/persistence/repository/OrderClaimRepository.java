package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, UUID> {
    /**
     * Admin read path (Phase 12/P4): optional status + claim-number
     * substring filter. Read-only; never used by business logic.
     */
    @Query("select c from OrderClaim c where (:status is null or c.status = :status) "
            + "and (:q is null or c.claimNumber ilike concat('%', cast(:q as string), '%'))")
    Page<OrderClaim> searchAdmin(
            @Param("status") ClaimStatus status, @Param("q") String q, Pageable pageable);

    Optional<OrderClaim> findByClaimNumber(String claimNumber);
    List<OrderClaim> findByOrderId(UUID orderId);

    /**
     * Aggregate read-path fetch: claims with their order item and support
     * ticket in one statement. The plain {@link #findByOrderId} leaves both
     * associations lazy, which turned the support-context claim history into
     * two queries per claim (item, then ticket).
     */
    @Query("select c from OrderClaim c "
            + "left join fetch c.orderItem "
            + "left join fetch c.supportTicket "
            + "where c.order.id = :orderId")
    List<OrderClaim> findByOrderIdWithDetails(@Param("orderId") UUID orderId);

    /**
     * Write-boundary pre-check for the active-claim domain invariant (see
     * {@code V7__dedupe_active_claims_per_item.sql}): at most one OPEN or
     * IN_REVIEW claim may exist per order item. The partial unique index is
     * the race-safe backstop; this check produces the clean domain error for
     * the sequential case.
     */
    boolean existsByOrderItemIdAndStatusIn(UUID orderItemId, Collection<ClaimStatus> statuses);

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
