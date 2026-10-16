package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OutboundCall;
import com.voxticket.persistence.entity.enums.OutboundCallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundCallRepository extends JpaRepository<OutboundCall, UUID> {
    Optional<OutboundCall> findByCallId(UUID callId);

    Optional<OutboundCall> findByIdempotencyKey(String idempotencyKey);

    /**
     * P4.1 (worker poll): queued calls, oldest first, so the worker dials
     * in trigger order. The service applies the limit clamp.
     */
    List<OutboundCall> findByStatusOrderByCreatedAtAsc(OutboundCallStatus status);
}
