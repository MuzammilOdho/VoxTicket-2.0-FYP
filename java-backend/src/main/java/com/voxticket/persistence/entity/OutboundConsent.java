package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * P4.1: per-customer outbound-call consent flag (V13).
 *
 * <p>Outbound calls are opt-in per customer. The default is "not
 * consented": a trigger for a customer with no row here - or with a row
 * whose flag is false - is refused with 403
 * ({@code OUTBOUND_CONSENT_REQUIRED}). Consent is set through
 * {@code POST /api/v1/voice/outbound/consent}; there is no campaign UI in
 * P4.
 */
@Entity
@Table(name = "outbound_consent",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbound_consent_customer", columnNames = "customer_id"))
public class OutboundConsent extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "consented", nullable = false)
    private boolean consented;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboundConsent() {
        // JPA
    }

    public OutboundConsent(UUID customerId, boolean consented, Instant updatedAt) {
        this.customerId = customerId;
        this.consented = consented;
        this.updatedAt = updatedAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public boolean isConsented() {
        return consented;
    }

    public void setConsented(boolean consented) {
        this.consented = consented;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
