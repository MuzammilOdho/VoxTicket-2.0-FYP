package com.voxticket.persistence.entity;

import com.voxticket.conversation.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageRecord extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionRecord session;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private MessageRole role;

    @NotNull
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationMessageRecord() {
        // JPA
    }

    public ConversationMessageRecord(ConversationSessionRecord session, int turnNumber, MessageRole role, String text) {
        this.session = session;
        this.turnNumber = turnNumber;
        this.role = role;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public ConversationSessionRecord getSession() {
        return session;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}