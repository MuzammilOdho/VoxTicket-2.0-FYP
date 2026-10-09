package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 12 (P4): per-turn AI decision trace for the admin UI. Flat shape
 * mirroring the frontend's {@code TurnTrace} contract field for field; null
 * means "not measured / not applicable for this turn" - never fabricated.
 * RAG documents are id/category/similarity references only; the
 * {@code decision} map carries redacted overflow detail (no OTP, secrets,
 * prompts, or reasoning).
 */
public record TurnTraceDto(
        String sessionId,
        int turnNumber,
        String channel,
        String traceId,
        String parentTraceId,
        Instant startedAt,
        Instant endedAt,
        String outcome,
        boolean aborted,
        Double normalizeMs,
        Double guardMs,
        Double routingMs,
        Double llmTtftMs,
        Double llmTotalMs,
        Double ragMs,
        Double toolMs,
        Boolean guardSuspicious,
        String guardCategory,
        String guardImplementation,
        Boolean guardFallback,
        String language,
        String intent,
        List<String> intentSignals,
        String routingStrategy,
        String tier,
        String routingReason,
        Double semanticMargin,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Boolean ragCacheHit,
        List<RagDoc> ragDocs,
        List<ToolCall> tools,
        String procedureType,
        String procedureStatus,
        String procedureOutcomeCode,
        String errorCode,
        Map<String, Object> decision) {

    public record RagDoc(String docId, String category, double similarity) {
    }

    public record ToolCall(String name, String result, double durationMs) {
    }
}
