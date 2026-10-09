package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceCallSessionEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only admin search over {@code voice_call_sessions} (V9) with
 * per-call turn-metric aggregates. Interface projection; no entity
 * hydration.
 */
public interface VoiceCallSearchRepository extends Repository<VoiceCallSessionEntity, UUID> {

    /** One voice call row plus aggregate stage latencies for admin lists. */
    interface VoiceCallRow {
        String getSessionId();
        String getRoom();
        String getTraceId();
        String getSttProvider();
        String getSttModel();
        String getTtsProvider();
        String getTtsModel();
        Instant getStartedAt();
        Instant getEndedAt();
        String getOutcome();
        Integer getBargeInCount();
        String getDisconnectReason();
        String getWorkerId();
        Long getTurnCount();
        Double getAvgSttLatencyMs();
        Double getAvgBrainTtftMs();
        Double getAvgTtsFirstAudioMs();
        Double getAvgE2eMs();
    }

    String SELECT = "SELECT s.session_id AS sessionId, c.room AS room, c.trace_id AS traceId, "
            + "c.stt_provider AS sttProvider, c.stt_model AS sttModel, "
            + "c.tts_provider AS ttsProvider, c.tts_model AS ttsModel, "
            + "c.started_at AS startedAt, c.ended_at AS endedAt, c.outcome AS outcome, "
            + "c.barge_in_count AS bargeInCount, c.disconnect_reason AS disconnectReason, "
            + "c.worker_id AS workerId, COUNT(m.id) AS turnCount, "
            + "CAST(AVG(m.stt_latency_ms) AS DOUBLE PRECISION) AS avgSttLatencyMs, "
            + "CAST(AVG(m.brain_ttft_ms) AS DOUBLE PRECISION) AS avgBrainTtftMs, "
            + "CAST(AVG(m.tts_first_audio_ms) AS DOUBLE PRECISION) AS avgTtsFirstAudioMs, "
            + "CAST(AVG(m.e2e_ms) AS DOUBLE PRECISION) AS avgE2eMs ";

    String FROM_WHERE = "FROM voice_call_sessions c "
            + "JOIN conversation_sessions s ON s.id = c.session_id "
            + "LEFT JOIN voice_call_turn_metrics m ON m.call_id = c.id "
            + "WHERE (CAST(:outcome AS VARCHAR) IS NULL OR c.outcome = CAST(:outcome AS VARCHAR)) "
            + "AND c.started_at >= :from AND c.started_at < :to ";

    String GROUP_BY = "GROUP BY s.session_id, c.room, c.trace_id, c.stt_provider, c.stt_model, "
            + "c.tts_provider, c.tts_model, c.started_at, c.ended_at, c.outcome, "
            + "c.barge_in_count, c.disconnect_reason, c.worker_id ";

    @Query(value = SELECT + FROM_WHERE + GROUP_BY,
            countQuery = "SELECT COUNT(*) FROM voice_call_sessions c "
                    + "WHERE (CAST(:outcome AS VARCHAR) IS NULL OR c.outcome = CAST(:outcome AS VARCHAR)) "
                    + "AND c.started_at >= :from AND c.started_at < :to",
            nativeQuery = true)
    Page<VoiceCallRow> search(
            @Param("outcome") String outcome,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
