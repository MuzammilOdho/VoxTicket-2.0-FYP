package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ConversationEventRecord;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationEventRecordRepository extends JpaRepository<ConversationEventRecord, UUID> {
    List<ConversationEventRecord> findBySessionIdOrderByCreatedAtAsc(UUID sessionRecordId);

    /**
     * Admin read path (Phase 12/P4): filterable audit event query.
     * Read-only; never used by business logic.
     *
     * The explicit {@code cast(... as Instant)} on the optional range bounds
     * is deliberate: a bare {@code :from is null} leaves a null parameter
     * untyped, and PostgreSQL then rejects the query with "could not
     * determine data type of parameter". Casting pins the parameter type.
     */
    @Query("select e from ConversationEventRecord e "
            + "where (:sessionId is null or e.session.sessionId = :sessionId) "
            + "and (:type is null or e.eventType = :type) "
            + "and (cast(:from as Instant) is null or e.createdAt >= :from) "
            + "and (cast(:to as Instant) is null or e.createdAt < :to)")
    Page<ConversationEventRecord> searchAdmin(
            @Param("sessionId") String sessionId,
            @Param("type") ConversationEventType type,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}