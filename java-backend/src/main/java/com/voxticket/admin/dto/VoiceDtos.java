package com.voxticket.admin.dto;

import java.time.Instant;
import java.util.List;

/** Phase 12 (P4): voice analytics DTOs, sourced from the V9 voice tables. */
public final class VoiceDtos {

    private VoiceDtos() {
    }

    public record VoiceCallSummaryDto(
            String sessionId,
            String room,
            String traceId,
            String sttProvider,
            String sttModel,
            String ttsProvider,
            String ttsModel,
            Instant startedAt,
            Instant endedAt,
            String outcome,
            int bargeInCount,
            String disconnectReason,
            String workerId,
            long turnCount,
            Double avgSttLatencyMs,
            Double avgBrainTtftMs,
            Double avgTtsFirstAudioMs,
            Double avgE2eMs) {
    }

    public record VoiceCallDetailDto(
            VoiceCallSummaryDto call,
            List<VoiceTurnMetricDto> turns) {
    }

    public record VoiceTurnMetricDto(
            int turnNumber,
            String traceId,
            Double sttLatencyMs,
            Double brainTtftMs,
            Double ttsFirstAudioMs,
            Double e2eMs,
            boolean aborted,
            boolean bargeIn,
            String sttLanguage,
            String error,
            Instant createdAt) {
    }
}
