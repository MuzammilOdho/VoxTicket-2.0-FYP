package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;

/** Phase 12 (P4): AI analytics, evaluation, and system health DTOs. */
public final class AiAnalyticsDtos {

    private AiAnalyticsDtos() {
    }

    public record ModelUsageDto(
            String provider,
            String model,
            String tier,
            long turns,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            double estimatedCostUsd) {
    }

    public record CostReportDto(
            double estimatedTotalUsd,
            long totalTokens,
            List<ModelUsageDto> byModel) {
    }

    public record RoutingStatsDto(
            List<RoutingBucket> distribution,
            Double marginP50,
            Double marginP95) {
    }

    public record RoutingBucket(
            String strategy,
            String tier,
            String reason,
            long count) {
    }

    public record RagStatsDto(
            long searches,
            Double cacheHitRate,
            Double meanLatencyMs,
            Double meanSimilarity,
            Double minSimilarity,
            Double maxSimilarity) {
    }

    /**
     * Aggregate quality signals computable without human labels. Label-based
     * accuracy (routing accuracy, RAG retrieval quality) needs a future
     * evaluation harness with a labeled set - see the report.
     */
    public record EvaluationSummaryDto(
            Double guardBlockRate,
            Double clarificationRate,
            Double escalationRate,
            Double abortRate,
            Double ragCacheHitRate,
            List<TierLatency> latencyByTier,
            List<ReasonCount> routing,
            String notes) {
    }

    public record TierLatency(String tier, Double p50, Double p95) {
    }

    public record ReasonCount(String reason, long count) {
    }

    public record LatencyByTier(String tier, Double p50Ms, Double p95Ms, long turns) {
    }

    public record NamedCount(String name, long count) {
    }

    public record SystemHealthDto(
            String status,
            Instant checkedAt,
            List<ComponentHealthDto> components) {
    }

    /** Frontend-facing AI analytics shapes (records serialize to JSON objects). */
    public record ModelAnalyticsDto(
            java.util.Map<String, Long> byTier,
            java.util.Map<String, Long> byProvider,
            java.util.Map<String, Long> byModel,
            java.util.Map<String, Long> tokensByProvider,
            Double costUsd) {
    }

    public record RoutingAnalyticsDto(
            java.util.Map<String, Long> byStrategy,
            java.util.Map<String, Long> byTier,
            java.util.Map<String, Long> byReason,
            Double marginP50,
            Double marginP95) {
    }

    public record RagAnalyticsDto(
            long searches,
            Double cacheHitRate,
            Double meanMs,
            Double similarityP50,
            Double similarityP95) {
    }

    public record CostAnalyticsDto(
            List<ModelCostEntry> perModel,
            Double totalUsd) {
    }

    public record ModelCostEntry(String model, long tokens, Double costUsd) {
    }

    public record ComponentHealthDto(
            String name,
            String status,
            String detail,
            Instant checkedAt) {
    }

    /**
     * Audit event row. Field names match the frontend's {@code AuditEvent}
     * contract ({@code type}, {@code at}).
     */
    public record AuditEventDto(
            String id,
            String sessionId,
            Integer turnNumber,
            String type,
            String detail,
            String traceId,
            Instant at) {
    }

    public record ToolStat(String name, long calls, Double successRate, Double meanMs) {
    }
}
