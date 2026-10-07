package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.TurnTraceRecordEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnTraceRecordRepository extends JpaRepository<TurnTraceRecordEntity, UUID> {
    java.util.List<TurnTraceRecordEntity> findBySession_IdOrderByTurnNumberAsc(UUID sessionRecordId);
    Optional<TurnTraceRecordEntity> findByTraceId(String traceId);

    /**
     * Voice calls share one traceId across all turns (the Python worker mints
     * one trace_id per room); the turn number disambiguates. Chat turns have
     * unique traceIds, so the turn number is simply an additional precision.
     */
    Optional<TurnTraceRecordEntity> findByTraceIdAndTurnNumber(String traceId, int turnNumber);
}
