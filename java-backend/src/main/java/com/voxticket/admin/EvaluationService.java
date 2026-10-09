package com.voxticket.admin;

import com.voxticket.admin.dto.AiAnalyticsDtos.EvaluationSummaryDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ReasonCount;
import com.voxticket.admin.dto.AiAnalyticsDtos.TierLatency;
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

    public EvaluationSummaryDto getSummary() {
        Double guardBlockRate = ratio(
                "SELECT COUNT(*) FILTER (WHERE outcome = 'blocked')::double precision / NULLIF(COUNT(*), 0) "
                        + "FROM conversation_turn_traces");
        Double clarificationRate = ratio(
                "SELECT COUNT(*) FILTER (WHERE event_type = 'PROCEDURE_CLARIFICATION')::double precision "
                        + "/ NULLIF((SELECT COUNT(*) FROM conversation_turn_traces), 0) "
                        + "FROM conversation_events");
        Double escalationRate = ratio(
                "SELECT COUNT(DISTINCT session_id) FILTER (WHERE event_type = 'ESCALATED')::double precision "
                        + "/ NULLIF((SELECT COUNT(*) FROM conversation_sessions), 0) "
                        + "FROM conversation_events");
        Double abortRate = ratio(
                "SELECT COUNT(*) FILTER (WHERE aborted)::double precision / NULLIF(COUNT(*), 0) "
                        + "FROM conversation_turn_traces");
        Double ragCacheHitRate = ratio(
                "SELECT AVG(CASE WHEN rag_cache_hit THEN 1.0 ELSE 0.0 END) "
                        + "FROM conversation_turn_traces WHERE rag_cache_hit IS NOT NULL");

        List<TierLatency> latencyByTier = jdbc.query(
                "SELECT tier, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY llm_total_ms) AS p50, "
                        + "percentile_cont(0.95) WITHIN GROUP (ORDER BY llm_total_ms) AS p95 "
                        + "FROM conversation_turn_traces WHERE llm_total_ms IS NOT NULL "
                        + "GROUP BY tier ORDER BY COUNT(*) DESC",
                (rs, n) -> new TierLatency(
                        rs.getString("tier"),
                        getNullable(rs, "p50"), getNullable(rs, "p95")));

        List<ReasonCount> routing = jdbc.query(
                "SELECT COALESCE(routing_reason, 'UNKNOWN') AS reason, COUNT(*) AS cnt "
                        + "FROM conversation_turn_traces GROUP BY 1 ORDER BY cnt DESC",
                (rs, n) -> new ReasonCount(rs.getString("reason"), rs.getLong("cnt")));

        return new EvaluationSummaryDto(
                guardBlockRate, clarificationRate, escalationRate, abortRate,
                ragCacheHitRate, latencyByTier, routing, NOTES);
    }

    private Double ratio(String sql) {
        Double v = jdbc.queryForObject(sql, Double.class);
        return v == null || v.isNaN() ? null : v;
    }

    private static Double getNullable(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Object o = rs.getObject(column);
        return o == null ? null : ((Number) o).doubleValue();
    }
}
