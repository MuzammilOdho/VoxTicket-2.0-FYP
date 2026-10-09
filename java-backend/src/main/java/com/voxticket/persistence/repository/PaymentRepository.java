package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.Payment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByOrderId(UUID orderId);
    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);
}