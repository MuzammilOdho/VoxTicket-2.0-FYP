package com.voxticket.admin;

import com.voxticket.admin.dto.AiAnalyticsDtos.CostAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.CostReportDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ModelAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ModelCostEntry;
import com.voxticket.admin.dto.AiAnalyticsDtos.ModelUsageDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RagAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RagStatsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RoutingAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RoutingBucket;
import com.voxticket.admin.dto.AiAnalyticsDtos.RoutingStatsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ToolStat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): read-only AI analytics over the durable
 * {@code conversation_turn_traces} table. Token counts are exact per-turn
 * sums; cost is an estimate from the configured per-model prices (unknown
 * prices contribute 0, never a fabricated number). Micrometer is not used
 * here - these numbers must survive restarts.
 */
@Service
@Transactional(readOnly = true)
public class AiAnalyticsService {

    private final JdbcTemplate jdbc;
    private final AdminCostProperties costProperties;

    public AiAnalyticsService(JdbcTemplate jdbc, AdminCostProperties costProperties) {
        this.jdbc = jdbc;
        this.costProperties = costProperties;
    }

    /** Token usage and estimated cost grouped by provider/model/tier. */
    public List<ModelUsageDto> modelUsage() {
        return jdbc.query(
                "SELECT provider, model, tier, COUNT(*) AS turns, "
                        + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                        + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens "
                        + "FROM conversation_turn_traces "
                        + "GROUP BY provider, model, tier ORDER BY turns DESC",
                (rs, n) -> {
                    long prompt = rs.getLong("prompt_tokens");
                    long completion = rs.getLong("completion_tokens");
                    long total = prompt + completion;
                    String model = rs.getString("model");
                    double cost = total / 1000.0 * costProperties.pricePer1kTokens(model);
                    return new ModelUsageDto(
                            rs.getString("provider"), model, rs.getString("tier"),
                            rs.getLong("turns"), prompt, completion, total, cost);
                });
    }

    public CostReportDto costReport() {
        List<ModelUsageDto> byModel = modelUsage();
        double total = byModel.stream().mapToDouble(ModelUsageDto::estimatedCostUsd).sum();
        long tokens = byModel.stream().mapToLong(ModelUsageDto::totalTokens).sum();
        return new CostReportDto(total, tokens, byModel);
    }

    /** Routing distribution plus semantic-margin percentiles (semantic path only). */
    public RoutingStatsDto routingStats() {
        List<RoutingBucket> distribution = jdbc.query(
                "SELECT routing_strategy, tier, routing_reason, COUNT(*) AS cnt "
                        + "FROM conversation_turn_traces "
                        + "GROUP BY routing_strategy, tier, routing_reason ORDER BY cnt DESC",
                (rs, n) -> new RoutingBucket(
                        rs.getString("routing_strategy"), rs.getString("tier"),
                        rs.getString("routing_reason"), rs.getLong("cnt")));
        List<Double[]> margins = jdbc.query(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY semantic_margin) AS p50, "
                        + "percentile_cont(0.95) WITHIN GROUP (ORDER BY semantic_margin) AS p95 "
                        + "FROM conversation_turn_traces WHERE semantic_margin IS NOT NULL",
                (rs, n) -> new Double[] {
                        rs.getObject("p50") == null ? null : rs.getDouble("p50"),
                        rs.getObject("p95") == null ? null : rs.getDouble("p95") });
        Double p50 = margins.isEmpty() ? null : margins.get(0)[0];
        Double p95 = margins.isEmpty() ? null : margins.get(0)[1];
        return new RoutingStatsDto(distribution, p50, p95);
    }

    /** RAG usage: search count, cache-hit rate, mean latency, similarity stats. */
    public RagStatsDto ragStats() {
        List<RagStatsDto> rows = jdbc.query(
                "SELECT COUNT(*) FILTER (WHERE rag_docs IS NOT NULL) AS searches, "
                        + "AVG(rag_ms) AS mean_ms, "
                        + "AVG(CASE WHEN rag_cache_hit THEN 1.0 ELSE 0.0 END) "
                        + "FILTER (WHERE rag_cache_hit IS NOT NULL) AS hit_rate "
                        + "FROM conversation_turn_traces",
                (rs, n) -> new RagStatsDto(
                        rs.getLong("searches"),
                        getNullableDouble(rs, "hit_rate"),
                        getNullableDouble(rs, "mean_ms"),
                        null, null, null));
        RagStatsDto base = rows.isEmpty()
                ? new RagStatsDto(0, null, null, null, null, null)
                : rows.get(0);
        List<Double[]> sim = jdbc.query(
                "SELECT AVG((d->>'similarity')::double precision) AS mean_sim, "
                        + "MIN((d->>'similarity')::double precision) AS min_sim, "
                        + "MAX((d->>'similarity')::double precision) AS max_sim "
                        + "FROM conversation_turn_traces, "
                        + "jsonb_array_elements(rag_docs) AS d "
                        + "WHERE rag_docs IS NOT NULL",
                (rs, n) -> new Double[] {
                        getNullableDouble(rs, "mean_sim"),
                        getNullableDouble(rs, "min_sim"),
                        getNullableDouble(rs, "max_sim") });
        Double mean = null, min = null, max = null;
        if (!sim.isEmpty()) {
            mean = sim.get(0)[0];
            min = sim.get(0)[1];
            max = sim.get(0)[2];
        }
        return new RagStatsDto(
                base.searches(), base.cacheHitRate(), base.meanLatencyMs(), mean, min, max);
    }

    /** Per-tool call counts, success rates, and mean durations from turn traces. */
    public List<ToolStat> toolStats() {
        return new ArrayList<>(jdbc.query(
                "SELECT t->>'name' AS name, COUNT(*) AS calls, "
                        + "AVG(CASE WHEN t->>'result' = 'OK' THEN 1.0 ELSE 0.0 END) AS success_rate, "
                        + "AVG((t->>'durationMs')::double precision) AS mean_ms "
                        + "FROM conversation_turn_traces, jsonb_array_elements(tools) AS t "
                        + "WHERE tools IS NOT NULL "
                        + "GROUP BY t->>'name' ORDER BY calls DESC",
                (rs, n) -> new ToolStat(
                        rs.getString("name"),
                        rs.getLong("calls"),
                        getNullableDouble(rs, "success_rate"),
                        getNullableDouble(rs, "mean_ms"))));
    }

    private static Double getNullableDouble(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Object o = rs.getObject(column);
        return o == null ? null : ((Number) o).doubleValue();
    }

    // ---- Frontend-facing shapes (records serialize to JSON objects) ----

    /** GET /api/v1/admin/ai/models */
    public ModelAnalyticsDto modelAnalytics() {
        List<ModelUsageDto> usage = modelUsage();
        Map<String, Long> byTier = new LinkedHashMap<>();
        Map<String, Long> byProvider = new LinkedHashMap<>();
        Map<String, Long> byModel = new LinkedHashMap<>();
        Map<String, Long> tokensByProvider = new LinkedHashMap<>();
        double costUsd = 0.0;
        for (ModelUsageDto u : usage) {
            byTier.merge(u.tier() == null ? "UNKNOWN" : u.tier(), u.turns(), Long::sum);
            byProvider.merge(u.provider() == null ? "UNKNOWN" : u.provider(), u.turns(), Long::sum);
            byModel.merge(u.model() == null ? "UNKNOWN" : u.model(), u.turns(), Long::sum);
            tokensByProvider.merge(
                    u.provider() == null ? "UNKNOWN" : u.provider(), u.totalTokens(), Long::sum);
            costUsd += u.estimatedCostUsd();
        }
        return new ModelAnalyticsDto(byTier, byProvider, byModel, tokensByProvider, costUsd);
    }

    /** GET /api/v1/admin/ai/routing */
    public RoutingAnalyticsDto routingAnalytics() {
        RoutingStatsDto stats = routingStats();
        Map<String, Long> byStrategy = new LinkedHashMap<>();
        Map<String, Long> byTier = new LinkedHashMap<>();
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (RoutingBucket b : stats.distribution()) {
            byStrategy.merge(
                    b.strategy() == null ? "UNKNOWN" : b.strategy(), b.count(), Long::sum);
            byTier.merge(b.tier() == null ? "UNKNOWN" : b.tier(), b.count(), Long::sum);
            byReason.merge(b.reason() == null ? "UNKNOWN" : b.reason(), b.count(), Long::sum);
        }
        return new RoutingAnalyticsDto(byStrategy, byTier, byReason, stats.marginP50(), stats.marginP95());
    }

    /** GET /api/v1/admin/ai/rag */
    public RagAnalyticsDto ragAnalytics() {
        RagStatsDto base = ragStats();
        List<Double[]> sim = jdbc.query(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY (d->>'similarity')::double precision) AS p50, "
                        + "percentile_cont(0.95) WITHIN GROUP (ORDER BY (d->>'similarity')::double precision) AS p95 "
                        + "FROM conversation_turn_traces, "
                        + "jsonb_array_elements(rag_docs) AS d "
                        + "WHERE rag_docs IS NOT NULL",
                (rs, n) -> new Double[] {
                        getNullableDouble(rs, "p50"), getNullableDouble(rs, "p95") });
        Double p50 = sim.isEmpty() ? null : sim.get(0)[0];
        Double p95 = sim.isEmpty() ? null : sim.get(0)[1];
        return new RagAnalyticsDto(
                base.searches(), base.cacheHitRate(), base.meanLatencyMs(), p50, p95);
    }

    /** GET /api/v1/admin/ai/cost */
    public CostAnalyticsDto costAnalytics() {
        CostReportDto report = costReport();
        List<ModelCostEntry> perModel = report.byModel().stream()
                .map(m -> new ModelCostEntry(m.model(), m.totalTokens(), m.estimatedCostUsd()))
                .toList();
        return new CostAnalyticsDto(perModel, report.estimatedTotalUsd());
    }
}
