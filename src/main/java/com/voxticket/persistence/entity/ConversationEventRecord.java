package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.ConversationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * detail is a short structured summary ONLY (tool name, outcome code,
 * model tier, RAG category count) - never raw model text, chain-of-
 * thought, reasoning, or the system prompt. Truncated defensively at
 * write time (ConversationAuditService) regardless of what the caller passes.
 */
@Entity
@Table(name = "conversation_events")
public class ConversationEventRecord extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionRecord session;

    @Column(name = "turn_number")
    private Integer turnNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private ConversationEventType eventType;

    @Size(max = 500)
    @Column(name = "detail", length = 500)
    private String detail;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationEventRecord() {
        // JPA
    }

    public ConversationEventRecord(ConversationSessionRecord session, Integer turnNumber, ConversationEventType eventType, String detail) {
        this.session = session;
        this.turnNumber = turnNumber;
        this.eventType = eventType;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public ConversationSessionRecord getSession() {
        return session;
    }

    public Integer getTurnNumber() {
        return turnNumber;
    }

    public ConversationEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}