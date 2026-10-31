package com.voxticket.admin;

import com.voxticket.admin.dto.AiAnalyticsDtos.EvaluationSummaryDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ReasonCount;
import com.voxticket.admin.dto.AiAnalyticsDtos.TierLatency;
import com.voxticket.admin.dto.AiAnalyticsDtos.VoiceTierLatency;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): aggregate quality signals computable from durable telemetry
 * alone. Rates are 0..1 doubles, null when undefined (no data).
 *
 * <p>Definitions: guardBlockRate = turns with outcome 'blocked' / all turns;
 * clarificationRate = PROCEDURE_CLARIFICATION events / all turns;
 * escalationRate = ESCALATED events / all sessions; abortRate = aborted
 * turns / all turns; resolutionRate (in the summary) = 'normal' outcomes /
 * all terminal turns.
 *
 * <p>Label-based accuracy (routing accuracy, RAG retrieval quality against
 * ground truth) cannot be computed from telemetry and needs a future
 * evaluation harness with a human-labeled set - see {@code notes}.
 *
 * <p>Phase 1 (admin rework): the summary accepts an optional date window
 * (see {@link WindowFilter}; traces by {@code ended_at}, events by
 * {@code created_at}, sessions by {@code started_at}, [from, to). The
 * no-argument call is exactly the old all-time summary.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationService {

    private static final String NOTES = "Aggregate signals only. Routing accuracy and RAG retrieval "
            + "quality against ground truth require a labeled evaluation set and a future eval harness; "
            + "they are intentionally not fabricated from telemetry.";

    private final JdbcTemplate jdbc;

    public EvaluationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All-time summary (the pre-Phase-1 behaviour). */
    public EvaluationSummaryDto getSummary() {
        return getSummary(null, null);
    }

    public EvaluationSummaryDto getSummary(Instant from, Instant to) {
        List<Object> guardParams = new ArrayList<>();
        Double guardBlockRate = ratio(
                "SELECT COUNT(*) FILTER (WHERE outcome = 'blocked')::double precision / NULLIF(COUNT(*), 0) "
                        + "FROM conversation_turn_traces WHERE 1=1"
                        + WindowFilter.and("ended_at", from, to, guardParams),
                guardParams);
        // The trace-count denominator is written before the event window in
        // the SQL text, so its parameters come first in the list.
        List<Object> clarificationParams = new ArrayList<>();
        StringBuilder clarificationSql = new StringBuilder(
                "SELECT COUNT(*) FILTER (WHERE event_type = 'PROCEDURE_CLARIFICATION')::double precision "
                        + "/ NULLIF((SELECT COUNT(*) FROM conversation_turn_traces WHERE 1=1");
        clarificationSql.append(WindowFilter.and("ended_at", from, to, clarificationParams));
        clarificationSql.append("), 0) FROM conversation_events WHERE 1=1");
        clarificationSql.append(WindowFilter.and("created_at", from, to, clarificationParams));
        Double clarificationRate = ratio(clarificationSql.toString(), clarificationParams);
        List<Object> escalationParams = new ArrayList<>();
        StringBuilder escalationSql = new StringBuilder(
                "SELECT COUNT(DISTINCT session_id) FILTER (WHERE event_type = 'ESCALATED')::double precision "
                        + "/ NULLIF((SELECT COUNT(*) FROM conversation_sessions WHERE 1=1");
        escalationSql.append(WindowFilter.and("started_at", from, to, escalationParams));
        escalationSql.append("), 0) FROM conversation_events WHERE 1=1");
        escalationSql.append(WindowFilter.and("created_at", from, to, escalationParams));
        Double escalationRate = ratio(escalationSql.toString(), escalationParams);
        List<Object> abortParams = new ArrayList<>();
        Double abortRate = ratio(
                "SELECT COUNT(*) FILTER (WHERE aborted)::double precision / NULLIF(COUNT(*), 0) "
                        + "FROM conversation_turn_traces WHERE 1=1"
                        + WindowFilter.and("ended_at", from, to, abortParams),
                abortParams);
        List<Object> ragParams = new ArrayList<>();
        Double ragCacheHitRate = ratio(
                "SELECT AVG(CASE WHEN rag_cache_hit THEN 1.0 ELSE 0.0 END) "
                        + "FROM conversation_turn_traces WHERE rag_cache_hit IS NOT NULL"
                        + WindowFilter.and("ended_at", from, to, ragParams),
                ragParams);

        List<Object> latencyParams = new ArrayList<>();
        List<TierLatency> latencyByTier = jdbc.query(
                "SELECT tier, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY llm_total_ms) AS p50, "
                        + "percentile_cont(0.95) WITHIN GROUP (ORDER BY llm_total_ms) AS p95 "
                        + "FROM conversation_turn_traces WHERE llm_total_ms IS NOT NULL "
                        + WindowFilter.and("ended_at", from, to, latencyParams)
                        + " GROUP BY tier ORDER BY COUNT(*) DESC",
                (rs, n) -> new TierLatency(
                        rs.getString("tier"),
                        getNullable(rs, "p50"), getNullable(rs, "p95")),
                latencyParams.toArray());

        List<Object> routingParams = new ArrayList<>();
        List<ReasonCount> routing = jdbc.query(
                "SELECT COALESCE(routing_reason, 'UNKNOWN') AS reason, COUNT(*) AS cnt "
                        + "FROM conversation_turn_traces WHERE 1=1"
                        + WindowFilter.and("ended_at", from, to, routingParams)
                        + " GROUP BY 1 ORDER BY cnt DESC",
                (rs, n) -> new ReasonCount(rs.getString("reason"), rs.getLong("cnt")),
                routingParams.toArray());

        // Voice stage latencies per tier, joined from the worker-emitted
        // turn metrics onto turn traces by trace_id. A turn counts in the
        // window where its trace ended, matching latencyByTier above.
        List<Object> voiceParams = new ArrayList<>();
        List<VoiceTierLatency> voiceLatencyByTier = jdbc.query(
                "SELECT COALESCE(t.tier, 'UNKNOWN') AS tier, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY m.stt_latency_ms) AS stt_p50, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY m.brain_ttft_ms) AS brain_p50, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY m.tts_first_audio_ms) AS tts_p50, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY m.e2e_ms) AS e2e_p50, "
                        + "percentile_cont(0.95) WITHIN GROUP (ORDER BY m.e2e_ms) AS e2e_p95, "
                        + "COUNT(*) AS turns "
                        + "FROM conversation_turn_traces t "
                        + "JOIN voice_call_turn_metrics m ON m.trace_id = t.trace_id WHERE 1=1"
                        + WindowFilter.and("t.ended_at", from, to, voiceParams)
                        + " GROUP BY 1 ORDER BY turns DESC",
                (rs, n) -> new VoiceTierLatency(
                        rs.getString("tier"),
                        getNullable(rs, "stt_p50"), getNullable(rs, "brain_p50"),
                        getNullable(rs, "tts_p50"), getNullable(rs, "e2e_p50"),
                        getNullable(rs, "e2e_p95"), rs.getLong("turns")),
                voiceParams.toArray());

        return new EvaluationSummaryDto(
                guardBlockRate, clarificationRate, escalationRate, abortRate,
                ragCacheHitRate, latencyByTier, routing, voiceLatencyByTier, NOTES);
    }

    private Double ratio(String sql, List<Object> params) {
        Double v = jdbc.queryForObject(sql, Double.class, params.toArray());
        return v == null || v.isNaN() ? null : v;
    }

    private static Double getNullable(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Object o = rs.getObject(column);
        return o == null ? null : ((Number) o).doubleValue();
    }
}
