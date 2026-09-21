package com.voxticket.admin.dto;

import java.util.Map;

public record AdminDashboardSummary(
        long totalConversations,
        long activeSessions,
        long procedureSuccessCount,
        long procedureFailureCount,
        long procedureClarificationCount,
        long escalationCount,
        Map<String, Long> modelTierUsage,
        Map<String, Long> tokenUsage,
        Double normalTurnLatencyP50Ms,
        Double normalTurnLatencyP95Ms,
        long totalTurnsProcessed,
        Map<String, ToolMetric> toolMetrics,
        RagMetric ragMetric) {

    public record ToolMetric(long callCount, double meanDurationMs) {
    }

    public record RagMetric(long searchCount, double meanDurationMs, double meanRetrievedCount) {
    }
}