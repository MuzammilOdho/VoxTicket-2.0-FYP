package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.OutboundCallStatus;
import com.voxticket.persistence.entity.enums.OutboundReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * P4.1: one API-triggered outbound call (V13).
 *
 * <p>Java's job ends at persistence: a trigger validates the request,
 * checks consent, and stores the row in {@link OutboundCallStatus#QUEUED}.
 * The Python worker polls/reads queued rows and dials - Java never
 * fabricates dial progress. The optional {@code idempotencyKey} makes
 * trigger retries safe: a repeated trigger with the same key returns the
 * original {@code callId} instead of queueing a second call.
 */
@Entity
@Table(name = "outbound_calls")
public class OutboundCall extends BaseEntity {

    @Column(name = "call_id", nullable = false, unique = true)
    private UUID callId;

    @Column(name = "customer_phone", nullable = false, length = 20)
    private String customerPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private OutboundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboundCallStatus status;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboundCall() {
        // JPA
    }

    public OutboundCall(UUID callId, String customerPhone, OutboundReason reason,
            OutboundCallStatus status, String idempotencyKey, Instant createdAt) {
        this.callId = callId;
        this.customerPhone = customerPhone;
        this.reason = reason;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public UUID getCallId() {
        return callId;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public OutboundReason getReason() {
        return reason;
    }

    public OutboundCallStatus getStatus() {
        return status;
    }

    /**
     * P4.1: worker-reported progress. Status transitions are validated by
     * {@link com.voxticket.api.voice.OutboundCallService#updateStatus} -
     * this setter itself performs no transition checks.
     */
    public void setStatus(OutboundCallStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
