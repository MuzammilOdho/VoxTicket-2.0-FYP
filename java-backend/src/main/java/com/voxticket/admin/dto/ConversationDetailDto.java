package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full conversation detail: session header plus a merged, time-ordered
 * timeline. Shape matches the frontend's {@code ConversationDetail}
 * contract: a nested {@code session} object and flat timeline entries
 * with {@code at} timestamps and human-readable {@code label}s.
 */
public record ConversationDetailDto(
        SessionInfo session,
        List<TimelineEntry> timeline) {

    public record SessionInfo(
            String sessionId,
            String channel,
            String status,
            String customerId,
            String identityAssurance,
            boolean escalated,
            int turnCount,
            Instant startedAt,
            Instant lastActivityAt) {
    }

    /**
     * One timeline row. kind is MESSAGE (user/assistant text), EVENT (typed
     * audit event), or TRACE (per-turn AI decision trace summary).
     */
    public record TimelineEntry(
            String kind,
            Integer turnNumber,
            Instant at,
            String label,
            String detail,
            String traceId) {
    }
}
