package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.TurnTraceRecordEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurnTraceRecordRepository extends JpaRepository<TurnTraceRecordEntity, UUID> {
    java.util.List<TurnTraceRecordEntity> findBySession_IdOrderByTurnNumberAsc(UUID sessionRecordId);
    Optional<TurnTraceRecordEntity> findByTraceId(String traceId);
}
