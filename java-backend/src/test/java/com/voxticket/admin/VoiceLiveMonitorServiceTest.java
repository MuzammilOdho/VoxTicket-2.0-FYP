package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.admin.dto.VoiceLiveMonitorDtos.LiveCallDto;
import com.voxticket.conversation.LiveSessionView;
import com.voxticket.conversation.SessionStore;
import com.voxticket.persistence.entity.VoiceWorkerHeartbeatEntity;
import com.voxticket.persistence.repository.VoiceWorkerHeartbeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P4.2: live-call DTO mapping over fixture snapshots. Plain unit tests -
 * no Spring context, no database.
 */
class VoiceLiveMonitorServiceTest {

    @Test
    void mapsSnapshotFieldsIncludingNulls() {
        SessionStore store = mock(SessionStore.class);
        VoiceWorkerHeartbeatRepository heartbeats = mock(VoiceWorkerHeartbeatRepository.class);
        when(store.liveSessions()).thenReturn(List.of(
                new LiveSessionView("s-1", "PHONE", 3, "CANCELLATION", "AWAITING_CONFIRMATION",
                        1, "CORRECTION", Map.of("orderNumber", 0.6)),
                new LiveSessionView("s-2", "PHONE", 1, null, null,
                        0, null, Map.of())));
        when(heartbeats.findAll()).thenReturn(List.of());

        List<LiveCallDto> calls = new VoiceLiveMonitorService(store, heartbeats).liveCalls();

        assertThat(calls).hasSize(2);
        LiveCallDto first = calls.get(0);
        assertThat(first.sessionId()).isEqualTo("s-1");
        assertThat(first.activeProcedureType()).isEqualTo("CANCELLATION");
        assertThat(first.activeProcedureStatus()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(first.pendingRequestDepth()).isEqualTo(1);
        assertThat(first.lastInterruptionClass()).isEqualTo("CORRECTION");
        assertThat(first.slotConfidences()).containsEntry("orderNumber", 0.6);
        assertThat(first.workerHeartbeatAgeSeconds()).isEqualTo(-1L);
        // No procedure / no interruption is null, not fabricated.
        assertThat(calls.get(1).activeProcedureType()).isNull();
        assertThat(calls.get(1).lastInterruptionClass()).isNull();
    }

    @Test
    void heartbeatAgeUsesMostRecentHeartbeat() {
        SessionStore store = mock(SessionStore.class);
        VoiceWorkerHeartbeatRepository heartbeats = mock(VoiceWorkerHeartbeatRepository.class);
        when(store.liveSessions()).thenReturn(List.of(
                new LiveSessionView("s-1", "PHONE", 2, null, null, 0, null, Map.of())));
        VoiceWorkerHeartbeatEntity old = new VoiceWorkerHeartbeatEntity("w-old");
        old.setLastSeenAt(Instant.now().minusSeconds(120));
        VoiceWorkerHeartbeatEntity fresh = new VoiceWorkerHeartbeatEntity("w-new");
        fresh.setLastSeenAt(Instant.now().minusSeconds(5));
        when(heartbeats.findAll()).thenReturn(List.of(old, fresh));

        List<LiveCallDto> calls = new VoiceLiveMonitorService(store, heartbeats).liveCalls();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).workerHeartbeatAgeSeconds()).isBetween(4L, 30L);
    }

    @Test
    void emptyStoreMeansEmptyMonitor() {
        SessionStore store = mock(SessionStore.class);
        VoiceWorkerHeartbeatRepository heartbeats = mock(VoiceWorkerHeartbeatRepository.class);
        when(store.liveSessions()).thenReturn(List.of());
        when(heartbeats.findAll()).thenReturn(List.of());

        assertThat(new VoiceLiveMonitorService(store, heartbeats).liveCalls()).isEmpty();
    }
}
