package com.voxticket.persistence.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

/**
 * Shared identity handling for every persistence entity.
 *
 * <p>Primary keys are application-generated UUIDs rather than sequential
 * longs. These UUIDs are internal JPA identifiers only - they are never the
 * thing a customer or the AI agent references (that is always a business
 * reference such as {@code orderNumber}/{@code ticketNumber}, scoped by
 * customer in the service layer added in Phase 2). Using UUIDs here simply
 * avoids leaking sequential counts and keeps IDs safe to log/trace.
 *
 * <p>equals/hashCode use the simplified (non-proxy-aware) id-based pattern:
 * two managed entities are equal iff same type and same non-null id. This is
 * sufficient for Phase 1 (no cross-session lazy-proxy comparisons yet). If a
 * later phase needs to compare across Hibernate proxies, upgrade to the
 * proxy-aware version instead of silently living with subtle bugs.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseEntity that = (BaseEntity) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}