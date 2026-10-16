package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.TurnTraceRecordEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TurnTraceRecordRepository extends JpaRepository<TurnTraceRecordEntity, UUID> {
    java.util.List<TurnTraceRecordEntity> findBySession_IdOrderByTurnNumberAsc(UUID sessionRecordId);
    Optional<TurnTraceRecordEntity> findByTraceId(String traceId);

    /**
     * Voice calls share one traceId across all turns (the Python worker mints
     * one trace_id per room); the turn number disambiguates. Chat turns have
     * unique traceIds, so the turn number is simply an additional precision.
     */
    Optional<TurnTraceRecordEntity> findByTraceIdAndTurnNumber(String traceId, int turnNumber);

    /**
     * P4.3: voice turns started in the window. Read-only aggregate source;
     * never used by business logic.
     */
    @Query("select count(t) from TurnTraceRecordEntity t "
            + "where t.channel = 'PHONE' and t.startedAt >= :from and t.startedAt < :to")
    long countVoiceTurns(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * P4.3: voice turns aborted because a newer generation superseded them
     * (the barge-in signal). Read-only aggregate source.
     */
    @Query("select count(t) from TurnTraceRecordEntity t "
            + "where t.channel = 'PHONE' and t.abortReason = 'superseded' "
            + "and t.startedAt >= :from and t.startedAt < :to")
    long countSupersededTurns(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * P4.3: per-session voice turn counts in the window, as
     * {@code Object[]{String sessionId, Long turnCount}}. The service
     * averages them; read-only aggregate source.
     */
    @Query("select t.session.sessionId, count(t) from TurnTraceRecordEntity t "
            + "where t.channel = 'PHONE' and t.startedAt >= :from and t.startedAt < :to "
            + "group by t.session.sessionId")
    List<Object[]> turnCountsPerSession(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * P4.3: most frequent intents in the window as
     * {@code Object[]{String intent, Long count}}, descending. Pass a
     * {@code PageRequest} to bound the rows. Read-only aggregate source.
     */
    @Query("select t.intent, count(t) from TurnTraceRecordEntity t "
            + "where t.channel = 'PHONE' and t.intent is not null and t.intent <> '' "
            + "and t.startedAt >= :from and t.startedAt < :to "
            + "group by t.intent order by count(t) desc")
    List<Object[]> topIntents(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
