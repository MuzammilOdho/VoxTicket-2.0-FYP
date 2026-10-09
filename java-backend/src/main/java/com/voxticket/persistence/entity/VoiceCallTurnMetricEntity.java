package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12 (P4): read-only view of {@code voice_call_turn_metrics} (V9).
 *
 * <p>Per-turn stage latencies. Each row links to its parent call via
 * {@code call_id}; the table carries no provider columns - the writer folds
 * per-turn provider info into the parent {@code voice_call_sessions} row.
 */
@Entity
@Table(name = "voice_call_turn_metrics")
public class VoiceCallTurnMetricEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "call_id")
    private UUID callId;

    @Column(name = "turn_number")
    private Integer turnNumber;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "stt_latency_ms")
    private Double sttLatencyMs;

    @Column(name = "brain_ttft_ms")
    private Double brainTtftMs;

    @Column(name = "tts_first_audio_ms")
    private Double ttsFirstAudioMs;

    @Column(name = "e2e_ms")
    private Double e2eMs;

    @Column(name = "aborted")
    private Boolean aborted;

    @Column(name = "barge_in")
    private Boolean bargeIn;

    @Column(name = "stt_language")
    private String sttLanguage;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    protected VoiceCallTurnMetricEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getCallId() {
        return callId;
    }

    public Integer getTurnNumber() {
        return turnNumber;
    }

    public String getTraceId() {
        return traceId;
    }

    public Double getSttLatencyMs() {
        return sttLatencyMs;
    }

    public Double getBrainTtftMs() {
        return brainTtftMs;
    }

    public Double getTtsFirstAudioMs() {
        return ttsFirstAudioMs;
    }

    public Double getE2eMs() {
        return e2eMs;
    }

    public Boolean getAborted() {
        return aborted;
    }

    public Boolean getBargeIn() {
        return bargeIn;
    }

    public String getSttLanguage() {
        return sttLanguage;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
