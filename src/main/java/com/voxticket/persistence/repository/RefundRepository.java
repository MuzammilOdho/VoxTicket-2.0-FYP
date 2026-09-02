package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByRefundNumber(String refundNumber);
    List<Refund> findByOrderId(UUID orderId);
    List<Refund> findByPaymentId(UUID paymentId);
}