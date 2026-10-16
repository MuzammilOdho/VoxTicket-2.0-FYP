package com.voxticket.conversation;

import java.util.Map;

/**
 * P4.2: an immutable point-in-time snapshot of one live voice session,
 * for the read-only supervisor monitor.
 *
 * <p>Built by the session store from defensive copies - never a live
 * view into session internals. Nullable fields (active procedure,
 * interruption class) mean "none right now", not "unknown".
 *
 * @param sessionId the session's business id
 * @param channel the channel name (PHONE sessions only in practice)
 * @param turnCount turns completed so far
 * @param activeProcedureType the live procedure's type, or null when none
 * @param activeProcedureStatus the live procedure's status, or null when none
 * @param pendingRequestDepth P2.2 pending-request stack depth
 * @param lastInterruptionClass P2.3 last interruption class, or null when none recorded
 * @param slotConfidences P2.1 slot name to current confidence (0.0-1.0)
 */
public record LiveSessionView(
        String sessionId,
        String channel,
        int turnCount,
        String activeProcedureType,
        String activeProcedureStatus,
        int pendingRequestDepth,
        String lastInterruptionClass,
        Map<String, Double> slotConfidences) {
}
