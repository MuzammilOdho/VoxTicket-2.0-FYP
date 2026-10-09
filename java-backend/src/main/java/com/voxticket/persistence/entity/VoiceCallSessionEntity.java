package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12 (P4): read-only view of {@code voice_call_sessions} (V9).
 *
 * <p>One row per LiveKit room; the async audit writer upserts by {@code room}.
 */
@Entity
@Table(name = "voice_call_sessions")
public class VoiceCallSessionEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "room")
    private String room;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "stt_provider")
    private String sttProvider;

    @Column(name = "stt_model")
    private String sttModel;

    @Column(name = "tts_provider")
    private String ttsProvider;

    @Column(name = "tts_model")
    private String ttsModel;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "barge_in_count")
    private Integer bargeInCount;

    @Column(name = "disconnect_reason")
    private String disconnectReason;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "created_at")
    private Instant createdAt;

    protected VoiceCallSessionEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getRoom() {
        return room;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSttProvider() {
        return sttProvider;
    }

    public String getSttModel() {
        return sttModel;
    }

    public String getTtsProvider() {
        return ttsProvider;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public Integer getBargeInCount() {
        return bargeInCount;
    }

    public String getDisconnectReason() {
        return disconnectReason;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
