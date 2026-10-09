package com.voxticket.api.voice;

import com.voxticket.api.voice.VoiceTelemetryDtos.VoiceCallDto;
import com.voxticket.api.voice.VoiceTelemetryDtos.VoiceTelemetryBatch;
import com.voxticket.api.voice.VoiceTelemetryDtos.VoiceTurnDto;
import com.voxticket.api.voice.VoiceTelemetryDtos.WorkerHeartbeatDto;
import com.voxticket.audit.AuditEvent;
import com.voxticket.audit.AuditEventBus;
import jakarta.validation.Valid;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 (P4): ingest endpoint for Python LiveKit worker telemetry.
 *
 * <p>The worker buffers per-turn stage latencies (STT, brain TTFT, TTS first
 * audio, end-to-end), barge-ins, disconnects and heartbeats, and POSTs them
 * here from a background task - never on the audio pipeline. This controller
 * only validates the envelope and publishes {@link AuditEvent}s to the
 * bounded {@link AuditEventBus}; persistence is asynchronous and
 * failure-isolated, so a telemetry failure can never fail a call.
 *
 * <p>Always answers 202, even when publishing fails (catch-all): telemetry
 * must never exert backpressure on the worker. Authentication is the
 * shared-secret {@code VoiceTelemetrySecretFilter}, not the ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/voice")
@Profile({"dev", "test"})
public class VoiceTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(VoiceTelemetryController.class);

    private final AuditEventBus auditEventBus;

    public VoiceTelemetryController(AuditEventBus auditEventBus) {
        this.auditEventBus = auditEventBus;
    }

    @PostMapping("/telemetry")
    public ResponseEntity<Void> ingest(@Valid @RequestBody VoiceTelemetryBatch batch) {
        try {
            if (batch != null) {
                // Calls first: the async writer applies per-turn provider
                // fields to the parent call row, so call rows should exist
                // before their turn metrics in the FIFO queue.
                if (batch.calls() != null) {
                    for (VoiceCallDto call : batch.calls()) {
                        if (call != null && call.room() != null && !call.room().isBlank()) {
                            publishCall(batch.workerId(), call);
                        }
                    }
                }
                if (batch.turns() != null) {
                    for (VoiceTurnDto turn : batch.turns()) {
                        if (turn != null && turn.room() != null && !turn.room().isBlank()) {
                            publishTurn(turn);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Telemetry must never fail the worker's request: 202 anyway.
            log.warn("event=voice_telemetry_ingest_failed errorType={}", e.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/worker-heartbeat")
    public ResponseEntity<Void> heartbeat(@Valid @RequestBody WorkerHeartbeatDto heartbeat) {
        try {
            if (heartbeat != null && heartbeat.workerId() != null && !heartbeat.workerId().isBlank()) {
                auditEventBus.publish(new AuditEvent.WorkerHeartbeat(
                        heartbeat.workerId(),
                        heartbeat.sttProvider(),
                        heartbeat.ttsProvider(),
                        heartbeat.activeRooms(),
                        Instant.now()));
            }
        } catch (Exception e) {
            log.warn("event=voice_heartbeat_ingest_failed errorType={}", e.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void publishCall(String workerId, VoiceCallDto call) {
        auditEventBus.publish(new AuditEvent.VoiceCallRecord(
                call.room(),
                call.room(),
                call.traceId(),
                null, // stt/tts provider+model arrive on the per-turn records
                null,
                null,
                null,
                call.startedAt(),
                call.endedAt(),
                call.outcome(),
                call.bargeInCount(),
                call.disconnectReason(),
                workerId,
                Instant.now()));
    }

    private void publishTurn(VoiceTurnDto turn) {
        auditEventBus.publish(new AuditEvent.VoiceTurnMetricRecord(
                turn.room(),
                turn.turnNumber(),
                turn.traceId(),
                turn.sttLatencyMs(),
                turn.brainTtftMs(),
                turn.ttsFirstAudioMs(),
                turn.e2eMs(),
                turn.aborted(),
                turn.bargeIn(),
                turn.sttLanguage(),
                turn.sttProvider(),
                turn.sttModel(),
                turn.ttsProvider(),
                turn.ttsModel(),
                turn.error(),
                Instant.now()));
    }
}
