package com.voxticket.admin;

import com.voxticket.admin.dto.AiAnalyticsDtos.AuditEventDto;
import com.voxticket.admin.dto.PageDto;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): read-only audit event query for the admin Audit Log page.
 * Filters by session, event type, and time range; newest first.
 */
@Service
@Transactional(readOnly = true)
public class AuditAdminService {

    private final ConversationEventRecordRepository eventRepository;

    public AuditAdminService(ConversationEventRecordRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public PageDto<AuditEventDto> searchEvents(
            String sessionId, String type, Instant from, Instant to, int page, int size) {
        ConversationEventType eventType = parseType(type);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditEventDto> result = eventRepository
                .searchAdmin(blankToNull(sessionId), eventType, from, to, pageable)
                .map(e -> new AuditEventDto(
                        e.getId() == null ? null : e.getId().toString(),
                        e.getSession() == null ? null : e.getSession().getSessionId(),
                        e.getTurnNumber(),
                        e.getEventType().name(),
                        e.getDetail(),
                        e.getTraceId(),
                        e.getCreatedAt()));
        return PageDto.of(result);
    }

    private static ConversationEventType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ConversationEventType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown event type: " + type);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
