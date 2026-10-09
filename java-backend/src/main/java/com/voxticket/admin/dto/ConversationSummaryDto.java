package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;

/** One conversation row for admin search results. */
public record ConversationSummaryDto(
        String sessionId,
        String channel,
        String status,
        String lastTurnOutcome,
        int turnCount,
        boolean escalated,
        Instant startedAt,
        Instant lastActivityAt) {
}
