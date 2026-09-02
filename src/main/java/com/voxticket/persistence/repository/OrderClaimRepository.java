package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OrderClaim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, UUID> {
    Optional<OrderClaim> findByClaimNumber(String claimNumber);
    List<OrderClaim> findByOrderId(UUID orderId);
}