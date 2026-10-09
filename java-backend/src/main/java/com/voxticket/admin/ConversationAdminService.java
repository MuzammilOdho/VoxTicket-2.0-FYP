package com.voxticket.admin;

import com.voxticket.admin.dto.ConversationDetailDto;
import com.voxticket.admin.dto.ConversationDetailDto.TimelineEntry;
import com.voxticket.admin.dto.ConversationSummaryDto;
import com.voxticket.admin.dto.PageDto;
import com.voxticket.persistence.entity.ConversationEventRecord;
import com.voxticket.persistence.entity.ConversationMessageRecord;
import com.voxticket.persistence.entity.ConversationSessionRecord;
import com.voxticket.persistence.entity.TurnTraceRecordEntity;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionSearchRepository;
import com.voxticket.persistence.repository.ConversationSessionSearchRepository.ConversationSessionRow;
import com.voxticket.persistence.repository.TurnTraceRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): read-only conversation queries for the admin UI.
 * Session status is derived, never stored: ACTIVE when the session saw
 * activity inside the active window, ABORTED when its last turn outcome was
 * "aborted", COMPLETED otherwise.
 */
@Service
@Transactional(readOnly = true)
public class ConversationAdminService {

    private final ConversationSessionSearchRepository searchRepository;
    private final ConversationSessionRecordRepository sessionRepository;
    private final ConversationMessageRecordRepository messageRepository;
    private final ConversationEventRecordRepository eventRepository;
    private final TurnTraceRecordRepository turnTraceRepository;
    private final long activeSessionWindowMinutes;

    public ConversationAdminService(
            ConversationSessionSearchRepository searchRepository,
            ConversationSessionRecordRepository sessionRepository,
            ConversationMessageRecordRepository messageRepository,
            ConversationEventRecordRepository eventRepository,
            TurnTraceRecordRepository turnTraceRepository,
            @Value("${voxticket.admin.active-session-window-minutes:15}") long activeSessionWindowMinutes) {
        this.searchRepository = searchRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.turnTraceRepository = turnTraceRepository;
        this.activeSessionWindowMinutes = activeSessionWindowMinutes;
    }

    public PageDto<ConversationSummaryDto> searchConversations(
            String channel,
            String outcome,
            Boolean escalated,
            String query,
            Instant from,
            Instant to,
            int page,
            int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), 200), Sort.by(Sort.Direction.DESC, "lastActivityAt"));
        Page<ConversationSessionRow> rows = searchRepository.search(
                normalizeChannel(channel),
                escalated,
                blankToNull(outcome),
                blankToNull(query),
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.now().plusSeconds(60) : to,
                pageable);
        Page<ConversationSummaryDto> mapped = rows.map(this::toSummary);
        return PageDto.of(mapped);
    }

    /** Session lifecycle status, derived with ACTIVE taking precedence over ABORTED. */
    public String deriveStatus(Instant lastActivityAt, String lastTurnOutcome) {
        if (lastActivityAt != null
                && lastActivityAt.isAfter(Instant.now().minusSeconds(activeSessionWindowMinutes * 60))) {
            return "ACTIVE";
        }
        if ("aborted".equalsIgnoreCase(lastTurnOutcome)) {
            return "ABORTED";
        }
        return "COMPLETED";
    }

    public ConversationDetailDto getDetail(String sessionId) {
        ConversationSessionRecord session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("No conversation found for session " + sessionId));

        List<ConversationMessageRecord> messages =
                messageRepository.findBySessionIdOrderByTurnNumberAsc(session.getId());
        List<ConversationEventRecord> events =
                eventRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<TurnTraceRecordEntity> traces =
                turnTraceRepository.findBySession_IdOrderByTurnNumberAsc(session.getId());

        List<TimelineEntry> timeline = new ArrayList<>();
        for (ConversationMessageRecord m : messages) {
            timeline.add(new TimelineEntry(
                    "MESSAGE", m.getTurnNumber(), m.getCreatedAt(), m.getRole().name(), m.getText(), null));
        }
        for (ConversationEventRecord e : events) {
            timeline.add(new TimelineEntry(
                    "EVENT", e.getTurnNumber(), e.getCreatedAt(), e.getEventType().name(), e.getDetail(),
                    e.getTraceId()));
        }
        for (TurnTraceRecordEntity t : traces) {
            timeline.add(new TimelineEntry(
                    "TRACE",
                    t.getTurnNumber(),
                    t.getEndedAt() != null ? t.getEndedAt() : t.getStartedAt(),
                    "Turn " + t.getTurnNumber() + " decision trace",
                    traceSummary(t),
                    t.getTraceId()));
        }
        timeline.sort(Comparator.comparing(
                TimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder())));

        ConversationDetailDto.SessionInfo info = new ConversationDetailDto.SessionInfo(
                session.getSessionId(),
                session.getChannel().name().toLowerCase(java.util.Locale.ROOT),
                deriveStatus(session.getLastActivityAt(), session.getLastTurnOutcome()),
                session.getCustomerId() == null ? null : session.getCustomerId().toString(),
                session.getIdentityAssurance().name(),
                session.isEscalated(),
                traces.isEmpty() ? distinctTurns(messages) : traces.size(),
                session.getStartedAt(),
                session.getLastActivityAt());
        return new ConversationDetailDto(info, List.copyOf(timeline));
    }

    private ConversationSummaryDto toSummary(ConversationSessionRow row) {
        return new ConversationSummaryDto(
                row.getSessionId(),
                row.getChannel() == null ? null : row.getChannel().toLowerCase(java.util.Locale.ROOT),
                deriveStatus(row.getLastActivityAt(), row.getLastTurnOutcome()),
                row.getLastTurnOutcome(),
                row.getTurnCount() == null ? 0 : row.getTurnCount(),
                row.getEscalated(),
                row.getStartedAt(),
                row.getLastActivityAt());
    }

    private static String traceSummary(TurnTraceRecordEntity t) {
        StringBuilder sb = new StringBuilder();
        sb.append("outcome=").append(t.getOutcome());
        if (t.isAborted()) {
            sb.append(" aborted=true");
        }
        if (t.getTier() != null) {
            sb.append(" tier=").append(t.getTier());
        }
        if (t.getModel() != null) {
            sb.append(" model=").append(t.getModel());
        }
        if (t.getRoutingReason() != null) {
            sb.append(" reason=").append(t.getRoutingReason());
        }
        if (Boolean.TRUE.equals(t.getGuardSuspicious())) {
            sb.append(" guard=BLOCKED");
        }
        if (t.getErrorCode() != null) {
            sb.append(" error=").append(t.getErrorCode());
        }
        return sb.toString();
    }

    private static int distinctTurns(List<ConversationMessageRecord> messages) {
        return (int) messages.stream().map(ConversationMessageRecord::getTurnNumber).distinct().count();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * The API accepts {@code chat}/{@code phone} (and the {@code voice}
     * alias) case-insensitively; the column stores the {@code Channel} enum
     * names {@code CHAT}/{@code PHONE}.
     */
    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String c = channel.trim().toUpperCase(java.util.Locale.ROOT);
        if (c.equals("VOICE")) {
            c = "PHONE";
        }
        return c;
    }
}
