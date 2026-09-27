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
