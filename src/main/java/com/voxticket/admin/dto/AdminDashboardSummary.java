package com.voxticket.admin.dto;

import java.util.Map;

/**
 * Phase 1 provider/model routing is surfaced here from live Micrometer
 * aggregates - the same tagged meters TurnMetrics records on the conversation
 * path (tier, provider, model, reason, outcome). These aggregates reset on
 * restart; per-turn detail (tier/provider/model/reason) is persisted in the
 * audit event log and visible in the conversation inspector timeline.
 *
 * No API keys, secrets, or reasoning content ever appear in these tags.
 */
public record AdminDashboardSummary(
        long totalConversations,
        long activeSessions,
        long procedureSuccessCount,
        long procedureFailureCount,
        long procedureClarificationCount,
        long escalationCount,
        Map<String, Long> modelTierUsage,
        Map<String, Long> modelProviderUsage,
        Map<String, Long> modelUsage,
        Map<String, Long> modelSelectionReasons,
        Map<String, Long> tokenUsage,
        Map<String, Long> tokenUsageByProvider,
        Map<String, LlmCallMetric> llmCallMetrics,
        Double normalTurnLatencyP50Ms,
        Double normalTurnLatencyP95Ms,
        long totalTurnsProcessed,
        Map<String, ToolMetric> toolMetrics,
        RagMetric ragMetric) {

    public record ToolMetric(long callCount, double meanDurationMs) {
    }

    /** Per-tier LLM call aggregates: total calls, mean latency, and error count. */
    public record LlmCallMetric(long callCount, double meanLatencyMs, long errorCount) {
    }

    public record RagMetric(long searchCount, double meanDurationMs, double meanRetrievedCount) {
    }
}
