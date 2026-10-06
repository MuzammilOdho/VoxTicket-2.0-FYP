package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ReturnRequest;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {
    Optional<ReturnRequest> findByReturnNumber(String returnNumber);
    List<ReturnRequest> findByOrderId(UUID orderId);

    /**
     * Aggregate read-path fetch: returns with their items and each item's
     * order item in one statement. The plain {@link #findByOrderId} leaves
     * {@code items} lazy, which turned the support-context return history
     * into an N+1 chain (one query per return, then one per return item).
     * {@code getReturnStatus} keeps the plain method - it renders no items.
     */
    @Query("select distinct r from ReturnRequest r "
            + "left join fetch r.items i "
            + "left join fetch i.orderItem "
            + "where r.order.id = :orderId")
    List<ReturnRequest> findByOrderIdWithItems(@Param("orderId") UUID orderId);

    /**
     * Row-locked return-request fetch for inspection completion.
     * The {@code PESSIMISTIC_WRITE} lock serializes concurrent inspections of
     * the same return request: without it, two simultaneous approvals could
     * both pass the state check and create duplicate refunds. Callers must
     * already run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnRequest r where r.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") UUID id);
}
