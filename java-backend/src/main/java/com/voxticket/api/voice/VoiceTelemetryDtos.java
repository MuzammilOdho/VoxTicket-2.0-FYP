package com.voxticket.api.voice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Phase 12 (P4): ingest DTOs for Python LiveKit worker telemetry.
 *
 * <p>JSON field names are the exact contract the worker sends; they mirror
 * the {@code voice_call_turn_metrics} / {@code voice_call_sessions} /
 * {@code voice_worker_heartbeats} columns. All latency fields are
 * milliseconds and nullable (null = not measured, never fabricated).
 */
public final class VoiceTelemetryDtos {

    private VoiceTelemetryDtos() {
    }

    public record VoiceTelemetryBatch(
            String workerId,
            @Valid List<VoiceTurnDto> turns,
            @Valid List<VoiceCallDto> calls) {
    }

    public record VoiceTurnDto(
            @NotBlank String room,
            int turnNumber,
            String traceId,
            Double sttLatencyMs,
            Double brainTtftMs,
            Double ttsFirstAudioMs,
            Double e2eMs,
            boolean aborted,
            boolean bargeIn,
            String sttLanguage,
            String sttProvider,
            String sttModel,
            String ttsProvider,
            String ttsModel,
            String error) {
    }

    public record VoiceCallDto(
            @NotBlank String room,
            String traceId,
            String outcome,
            int bargeInCount,
            String disconnectReason,
            Instant startedAt,
            Instant endedAt) {
    }

    public record WorkerHeartbeatDto(
            @NotNull String workerId,
            String sttProvider,
            String ttsProvider,
            int activeRooms) {
    }
}
