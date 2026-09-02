package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ReturnRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {
    Optional<ReturnRequest> findByReturnNumber(String returnNumber);
    List<ReturnRequest> findByOrderId(UUID orderId);
}