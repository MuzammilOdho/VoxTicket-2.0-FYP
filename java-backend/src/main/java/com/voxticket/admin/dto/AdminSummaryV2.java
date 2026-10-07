package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 12 (P4): v2 dashboard summary. Aggregates durable turn traces
 * (Postgres) for anything per-conversation, and in-memory Micrometer meters
 * for rates that reset on restart. No metric is treated as a database.
 */
public record AdminSummaryV2(
        Totals totals,
        ByChannel byChannel,
        Rates rates,
        Latency latency,
        Models models,
        Tokens tokens,
        Cost cost,
        List<ToolStat> tools,
        RagStats rag,
        Procedures procedures,
        List<RecentError> recentErrors) {

    public record Totals(long total, long active, long completed, long aborted) {
    }

    public record ByChannel(long chat, long voice) {
    }

    /** Ratios 0..1, null when undefined. Resolution = turns with outcome 'normal' / all turns. */
    public record Rates(Double resolutionRate, Double escalationRate) {
    }

    public record Latency(Double turnP50Ms, Double turnP95Ms) {
    }

    public record Models(
            Map<String, Long> byTier,
            Map<String, Long> byProvider,
            Map<String, Long> byModel) {
    }

    public record Tokens(long total, Map<String, Long> byProvider) {
    }

    /** Estimated USD spend from configured per-model prices; unknown prices are excluded, never zero-filled. */
    public record Cost(double estimatedUsd, List<ModelCost> byModel) {
    }

    public record ModelCost(String model, long tokens, double estimatedUsd) {
    }

    public record ToolStat(String name, long calls, Double successRate, Double meanMs) {
    }

    public record RagStats(long searches, Double cacheHitRate, Double meanMs) {
    }

    public record Procedures(long success, long failure, long clarification) {
    }

    public record RecentError(
            String traceId,
            String sessionId,
            int turnNumber,
            String outcome,
            String errorCode,
            Instant at) {
    }
}
