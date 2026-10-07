package com.voxticket.admin;

import com.voxticket.admin.dto.AdminSummaryV2;
import com.voxticket.admin.dto.AdminSummaryV2.ByChannel;
import com.voxticket.admin.dto.AdminSummaryV2.Cost;
import com.voxticket.admin.dto.AdminSummaryV2.Latency;
import com.voxticket.admin.dto.AdminSummaryV2.Models;
import com.voxticket.admin.dto.AdminSummaryV2.Procedures;
import com.voxticket.admin.dto.AdminSummaryV2.RagStats;
import com.voxticket.admin.dto.AdminSummaryV2.Rates;
import com.voxticket.admin.dto.AdminSummaryV2.RecentError;
import com.voxticket.admin.dto.AdminSummaryV2.Tokens;
import com.voxticket.admin.dto.AdminSummaryV2.ToolStat;
import com.voxticket.admin.dto.AdminSummaryV2.Totals;
import com.voxticket.admin.dto.AiAnalyticsDtos.CostReportDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ModelUsageDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RagStatsDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): v2 dashboard summary. Durable numbers come from Postgres
 * (turn traces, sessions, events); nothing here depends on in-memory
 * Micrometer state, so the numbers survive restarts.
 */
@Service
@Transactional(readOnly = true)
public class AdminSummaryService {

    private final JdbcTemplate jdbc;
    private final AiAnalyticsService aiAnalyticsService;
    private final int activeWindowMinutes;

    public AdminSummaryService(
            JdbcTemplate jdbc,
            AiAnalyticsService aiAnalyticsService,
            @Value("${voxticket.admin.active-session-window-minutes:15}") int activeWindowMinutes) {
        this.jdbc = jdbc;
        this.aiAnalyticsService = aiAnalyticsService;
        this.activeWindowMinutes = activeWindowMinutes;
    }

    public AdminSummaryV2 getSummary() {
        Totals totals = totals();
        ByChannel byChannel = byChannel();
        Rates rates = rates(totals);
        Latency latency = latency();
        List<ModelUsageDto> usage = aiAnalyticsService.modelUsage();
        Models models = models(usage);
        Tokens tokens = tokens(usage);
        CostReportDto costReport = aiAnalyticsService.costReport();
        Cost cost = new Cost(costReport.estimatedTotalUsd(),
                costReport.byModel().stream()
                        .map(m -> new AdminSummaryV2.ModelCost(
                                m.model(), m.totalTokens(), m.estimatedCostUsd()))
                        .toList());
        List<ToolStat> tools = aiAnalyticsService.toolStats().stream()
                .map(t -> new ToolStat(t.name(), t.calls(), t.successRate(), t.meanMs()))
                .toList();
        RagStatsDto rag = aiAnalyticsService.ragStats();
        return new AdminSummaryV2(
                totals, byChannel, rates, latency, models, tokens, cost, tools,
                new RagStats(rag.searches(), rag.cacheHitRate(), rag.meanLatencyMs()),
                procedures(),
                recentErrors());
    }

    private Totals totals() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        RowCallbackHandler handler = rs ->
                byStatus.put(rs.getString("status"), rs.getLong("cnt"));
        jdbc.query(
                "SELECT status, COUNT(*) AS cnt FROM ("
                        + "SELECT CASE WHEN last_activity_at > now() - make_interval(mins => ?) THEN 'ACTIVE' "
                        + "WHEN last_turn_outcome = 'aborted' THEN 'ABORTED' "
                        + "ELSE 'COMPLETED' END AS status "
                        + "FROM conversation_sessions) s GROUP BY status",
                handler,
                activeWindowMinutes);
        long active = byStatus.getOrDefault("ACTIVE", 0L);
        long aborted = byStatus.getOrDefault("ABORTED", 0L);
        long completed = byStatus.getOrDefault("COMPLETED", 0L);
        return new Totals(active + aborted + completed, active, completed, aborted);
    }

    private ByChannel byChannel() {
        Map<String, Long> counts = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> counts.put(rs.getString("channel"), rs.getLong("cnt"));
        jdbc.query("SELECT channel, COUNT(*) AS cnt FROM conversation_sessions GROUP BY channel", handler);
        return new ByChannel(
                counts.getOrDefault("CHAT", 0L),
                counts.getOrDefault("PHONE", 0L));
    }

    private Rates rates(Totals totals) {
        if (totals.total() == 0) {
            return new Rates(null, null);
        }
        Double escalationRate = jdbc.queryForObject(
                "SELECT COUNT(*) FILTER (WHERE escalated)::double precision / COUNT(*) "
                        + "FROM conversation_sessions",
                Double.class);
        Double resolutionRate = jdbc.queryForObject(
                "SELECT COUNT(*) FILTER (WHERE outcome = 'normal')::double precision "
                        + "/ NULLIF(COUNT(*) FILTER (WHERE outcome IS NOT NULL), 0) "
                        + "FROM conversation_turn_traces",
                Double.class);
        return new Rates(resolutionRate, escalationRate);
    }

    private Latency latency() {
        List<Double[]> rows = jdbc.query(
                "SELECT percentile_cont(0.5) WITHIN GROUP ("
                        + "ORDER BY EXTRACT(EPOCH FROM (ended_at - started_at)) * 1000) AS p50, "
                        + "percentile_cont(0.95) WITHIN GROUP ("
                        + "ORDER BY EXTRACT(EPOCH FROM (ended_at - started_at)) * 1000) AS p95 "
                        + "FROM conversation_turn_traces WHERE ended_at IS NOT NULL",
                (rs, n) -> new Double[] {
                        getNullable(rs, "p50"), getNullable(rs, "p95") });
        if (rows.isEmpty()) {
            return new Latency(null, null);
        }
        return new Latency(rows.get(0)[0], rows.get(0)[1]);
    }

    private Models models(List<ModelUsageDto> usage) {
        Map<String, Long> byModel = new LinkedHashMap<>();
        for (ModelUsageDto u : usage) {
            byModel.merge(u.model() == null ? "UNKNOWN" : u.model(), u.turns(), Long::sum);
        }
        return new Models(
                countBy(usage, ModelUsageDto::tier),
                countBy(usage, ModelUsageDto::provider),
                byModel);
    }

    private Tokens tokens(List<ModelUsageDto> usage) {
        long total = usage.stream().mapToLong(ModelUsageDto::totalTokens).sum();
        Map<String, Long> byProvider = new LinkedHashMap<>();
        for (ModelUsageDto u : usage) {
            byProvider.merge(u.provider() == null ? "UNKNOWN" : u.provider(), u.totalTokens(), Long::sum);
        }
        return new Tokens(total, byProvider);
    }

    private Procedures procedures() {
        Map<String, Long> counts = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> counts.put(rs.getString("event_type"), rs.getLong("cnt"));
        jdbc.query(
                "SELECT event_type, COUNT(*) AS cnt FROM conversation_events "
                        + "WHERE event_type IN ('PROCEDURE_COMPLETED', 'PROCEDURE_FAILED', 'PROCEDURE_CLARIFICATION') "
                        + "GROUP BY event_type",
                handler);
        return new Procedures(
                counts.getOrDefault("PROCEDURE_COMPLETED", 0L),
                counts.getOrDefault("PROCEDURE_FAILED", 0L),
                counts.getOrDefault("PROCEDURE_CLARIFICATION", 0L));
    }

    private List<RecentError> recentErrors() {
        return new ArrayList<>(jdbc.query(
                "SELECT t.trace_id, s.session_id, t.turn_number, t.outcome, t.error_code, t.ended_at "
                        + "FROM conversation_turn_traces t "
                        + "JOIN conversation_sessions s ON s.id = t.session_id "
                        + "WHERE t.aborted OR t.error_code IS NOT NULL "
                        + "OR t.outcome IN ('blocked', 'agent_error_recovered', 'blank_fallback') "
                        + "ORDER BY t.ended_at DESC NULLS LAST LIMIT 20",
                (rs, n) -> new RecentError(
                        rs.getString("trace_id"),
                        rs.getString("session_id"),
                        rs.getInt("turn_number"),
                        rs.getString("outcome"),
                        rs.getString("error_code"),
                        toInstant(rs.getTimestamp("ended_at")))));
    }

    private static Map<String, Long> countBy(
            List<ModelUsageDto> usage, java.util.function.Function<ModelUsageDto, String> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ModelUsageDto u : usage) {
            String k = key.apply(u);
            counts.merge(k == null ? "UNKNOWN" : k, u.turns(), Long::sum);
        }
        return counts;
    }

    private static Double getNullable(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Object o = rs.getObject(column);
        return o == null ? null : ((Number) o).doubleValue();
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
