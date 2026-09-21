package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;

public record ConversationInspectorView(
        String sessionId, String channel, String identityAssurance, boolean escalated,
        Instant startedAt, Instant lastActivityAt, List<TimelineEntry> timeline) {

    public record TimelineEntry(Integer turnNumber, String kind, String role, String eventType, String text, Instant timestamp) {
    }
}