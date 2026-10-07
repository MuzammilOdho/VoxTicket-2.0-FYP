package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ConversationSessionRecord;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only admin search over {@code conversation_sessions} (V6 + V9
 * lifecycle columns). Interface projection avoids hydrating entities; the
 * V9 columns ({@code turn_count}, {@code last_turn_outcome}) are read here
 * without touching {@code ConversationSessionRecord}, which the audit
 * writer owns.
 */
public interface ConversationSessionSearchRepository extends Repository<ConversationSessionRecord, UUID> {

    /** Flat projection of one conversation_sessions row for admin search. */
    interface ConversationSessionRow {
        String getSessionId();
        String getChannel();
        boolean getEscalated();
        Instant getStartedAt();
        Instant getLastActivityAt();
        Integer getTurnCount();
        String getLastTurnOutcome();
    }

    String WHERE = "FROM conversation_sessions s "
            + "WHERE (CAST(:channel AS VARCHAR) IS NULL OR s.channel = CAST(:channel AS VARCHAR)) "
            + "AND (CAST(:escalated AS BOOLEAN) IS NULL OR s.escalated = CAST(:escalated AS BOOLEAN)) "
            + "AND (CAST(:outcome AS VARCHAR) IS NULL OR s.last_turn_outcome = CAST(:outcome AS VARCHAR)) "
            + "AND (CAST(:query AS VARCHAR) IS NULL OR s.session_id ILIKE '%' || CAST(:query AS VARCHAR) || '%') "
            + "AND s.started_at >= :from AND s.started_at < :to ";

    @Query(value = "SELECT s.session_id AS sessionId, s.channel AS channel, s.escalated AS escalated, "
            + "s.started_at AS startedAt, s.last_activity_at AS lastActivityAt, "
            + "s.turn_count AS turnCount, s.last_turn_outcome AS lastTurnOutcome "
            + WHERE,
            countQuery = "SELECT COUNT(*) " + WHERE,
            nativeQuery = true)
    Page<ConversationSessionRow> search(
            @Param("channel") String channel,
            @Param("escalated") Boolean escalated,
            @Param("outcome") String outcome,
            @Param("query") String query,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
