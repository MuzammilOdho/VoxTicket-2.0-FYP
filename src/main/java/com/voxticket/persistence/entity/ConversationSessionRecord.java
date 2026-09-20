package com.voxticket.persistence.entity;

import com.voxticket.conversation.Channel;
import com.voxticket.identity.IdentityAssurance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_sessions")
public class ConversationSessionRecord extends BaseEntity {

    @NotNull
    @Size(max = 100)
    @Column(name = "session_id", nullable = false, unique = true, length = 100)
    private String sessionId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private Channel channel;

    @Column(name = "customer_id")
    private UUID customerId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_assurance", nullable = false, length = 20)
    private IdentityAssurance identityAssurance;

    @Column(name = "escalated", nullable = false)
    private boolean escalated = false;

    @NotNull
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @NotNull
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    protected ConversationSessionRecord() {
        // JPA
    }

    public ConversationSessionRecord(String sessionId, Channel channel, IdentityAssurance identityAssurance) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.identityAssurance = identityAssurance;
        this.startedAt = Instant.now();
        this.lastActivityAt = this.startedAt;
    }

    public void touch(UUID customerId, IdentityAssurance identityAssurance, boolean escalated) {
        this.customerId = customerId;
        this.identityAssurance = identityAssurance;
        this.escalated = escalated;
        this.lastActivityAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public IdentityAssurance getIdentityAssurance() {
        return identityAssurance;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }
}