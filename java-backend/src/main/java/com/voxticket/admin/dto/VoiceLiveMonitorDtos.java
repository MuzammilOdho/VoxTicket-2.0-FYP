package com.voxticket.admin.dto;

import java.util.Map;

/**
 * P4.2: DTOs for the read-only supervisor live-call monitor.
 *
 * <p>Every field is a snapshot of in-memory state - nothing here is a
 * live reference, and nothing here can mutate a call.
 */
public final class VoiceLiveMonitorDtos {

    private VoiceLiveMonitorDtos() {
    }

    /**
     * One live voice session.
     *
     * @param sessionId the session's business id
     * @param channel always PHONE in practice (the monitor is a voice view)
     * @param turnCount turns completed so far
     * @param activeProcedureType live procedure type, or null when none
     * @param activeProcedureStatus live procedure status, or null when none
     * @param pendingRequestDepth P2.2 pending-request stack depth
     * @param lastInterruptionClass P2.3 last interruption class, or null when none recorded
     * @param slotConfidences P2.1 slot name to current confidence (0.0-1.0)
     * @param workerHeartbeatAgeSeconds seconds since the last worker
     *        heartbeat seen by the backend, or -1 when none was ever seen
     */
    public record LiveCallDto(
            String sessionId,
            String channel,
            int turnCount,
            String activeProcedureType,
            String activeProcedureStatus,
            int pendingRequestDepth,
            String lastInterruptionClass,
            Map<String, Double> slotConfidences,
            long workerHeartbeatAgeSeconds) {
    }
}
