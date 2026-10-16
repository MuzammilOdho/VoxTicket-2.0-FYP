package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceCallSessionEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceCallSessionRepository extends JpaRepository<VoiceCallSessionEntity, UUID> {
    Optional<VoiceCallSessionEntity> findByRoom(String room);
    Optional<VoiceCallSessionEntity> findBySessionId(UUID sessionRecordId);

    /**
     * P4.3: voice calls started in the window. Read-only aggregate source;
     * never used by business logic.
     */
    long countByStartedAtGreaterThanEqualAndStartedAtLessThan(Instant from, Instant to);

    /**
     * P4.3: calls in the window the worker reported as completed. The
     * outcome is worker-reported free text, so the match is a defensive
     * lowercase equality - never fabricated, never assumed. Read-only
     * aggregate source.
     */
    @Query("select count(v) from VoiceCallSessionEntity v "
            + "where v.startedAt >= :from and v.startedAt < :to and lower(v.outcome) = 'completed'")
    long countCompletedInWindow(@Param("from") Instant from, @Param("to") Instant to);
}
