package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Phase 12 (P4): read-only view of {@code voice_worker_heartbeats} (V9).
 *
 * <p>Last-seen per worker id (upserted by the writer); {@code worker_id} is
 * the primary key.
 */
@Entity
@Table(name = "voice_worker_heartbeats")
public class VoiceWorkerHeartbeatEntity {

    @Id
    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "stt_provider")
    private String sttProvider;

    @Column(name = "tts_provider")
    private String ttsProvider;

    @Column(name = "active_rooms")
    private Integer activeRooms;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected VoiceWorkerHeartbeatEntity() {
    }

    /** Test/support constructor; production rows are written by the audit writer. */
    public VoiceWorkerHeartbeatEntity(String workerId) {
        this.workerId = workerId;
    }

    public void setSttProvider(String sttProvider) {
        this.sttProvider = sttProvider;
    }

    public void setTtsProvider(String ttsProvider) {
        this.ttsProvider = ttsProvider;
    }

    public void setActiveRooms(Integer activeRooms) {
        this.activeRooms = activeRooms;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getSttProvider() {
        return sttProvider;
    }

    public String getTtsProvider() {
        return ttsProvider;
    }

    public Integer getActiveRooms() {
        return activeRooms;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
