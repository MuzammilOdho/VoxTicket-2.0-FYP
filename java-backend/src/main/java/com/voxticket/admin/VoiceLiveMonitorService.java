package com.voxticket.admin;

import com.voxticket.admin.dto.VoiceLiveMonitorDtos.LiveCallDto;
import com.voxticket.conversation.LiveSessionView;
import com.voxticket.conversation.SessionStore;
import com.voxticket.persistence.entity.VoiceWorkerHeartbeatEntity;
import com.voxticket.persistence.repository.VoiceWorkerHeartbeatRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4.2: the read-only supervisor live-call monitor.
 *
 * <p>Builds the live-call view purely from in-memory state (session
 * snapshots) plus the last worker heartbeat row. <strong>Read-only by
 * design:</strong> P4 exposes no whisper, no barge-in, and no hang-up -
 * those would let a dashboard operator interfere with a live caller, which
 * is too risky for this phase. The service returns snapshots; nothing it
 * hands out can mutate a session.
 */
@Service
@Transactional(readOnly = true)
public class VoiceLiveMonitorService {

    private final SessionStore sessionStore;
    private final VoiceWorkerHeartbeatRepository heartbeatRepository;

    public VoiceLiveMonitorService(SessionStore sessionStore,
            VoiceWorkerHeartbeatRepository heartbeatRepository) {
        this.sessionStore = sessionStore;
        this.heartbeatRepository = heartbeatRepository;
    }

    /**
     * Current live voice sessions, most-recently-active first is not
     * guaranteed - the store returns sessions in no particular order.
     */
    public List<LiveCallDto> liveCalls() {
        Instant now = Instant.now();
        long heartbeatAgeSeconds = heartbeatRepository.findAll().stream()
                .map(VoiceWorkerHeartbeatEntity::getLastSeenAt)
                .filter(seen -> seen != null)
                .max(Instant::compareTo)
                .map(seen -> Duration.between(seen, now).getSeconds())
                .orElse(-1L);
        return sessionStore.liveSessions().stream()
                .map(view -> toDto(view, heartbeatAgeSeconds))
                .toList();
    }

    private static LiveCallDto toDto(LiveSessionView view, long heartbeatAgeSeconds) {
        return new LiveCallDto(
                view.sessionId(),
                view.channel(),
                view.turnCount(),
                view.activeProcedureType(),
                view.activeProcedureStatus(),
                view.pendingRequestDepth(),
                view.lastInterruptionClass(),
                view.slotConfidences(),
                heartbeatAgeSeconds);
    }
}
