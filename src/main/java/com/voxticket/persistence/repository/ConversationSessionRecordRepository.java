package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ConversationSessionRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSessionRecordRepository extends JpaRepository<ConversationSessionRecord, UUID> {
    Optional<ConversationSessionRecord> findBySessionId(String sessionId);
    long countByLastActivityAtAfter(Instant cutoff);
}