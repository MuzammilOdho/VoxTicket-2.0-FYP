package com.voxticket.admin.dto;

import java.time.Instant;

public record ConversationSummaryView(
        String sessionId, String channel, String identityAssurance, boolean escalated, Instant startedAt, Instant lastActivityAt) {
}