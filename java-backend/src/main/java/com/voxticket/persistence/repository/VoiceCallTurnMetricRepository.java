package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceCallTurnMetricEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceCallTurnMetricRepository extends JpaRepository<VoiceCallTurnMetricEntity, UUID> {
    List<VoiceCallTurnMetricEntity> findByCallIdOrderByTurnNumberAsc(UUID callId);
    long countByCallId(UUID callId);

    /**
     * P4.3: voice turn metrics recorded in the window. Read-only aggregate
     * source; never used by business logic.
     */
    @Query("select count(m) from VoiceCallTurnMetricEntity m "
            + "where m.createdAt >= :from and m.createdAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * P4.3: P1.4 mid-stream stalls recorded in the window. Read-only
     * aggregate source.
     */
    @Query("select count(m) from VoiceCallTurnMetricEntity m "
            + "where m.stalled = true and m.createdAt >= :from and m.createdAt < :to")
    long countStalledInWindow(@Param("from") Instant from, @Param("to") Instant to);
}
