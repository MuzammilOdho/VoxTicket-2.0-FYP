package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OutboundConsent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundConsentRepository extends JpaRepository<OutboundConsent, UUID> {
    Optional<OutboundConsent> findByCustomerId(UUID customerId);
}
