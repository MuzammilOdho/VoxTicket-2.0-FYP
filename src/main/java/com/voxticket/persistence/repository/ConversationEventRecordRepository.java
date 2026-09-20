package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ConversationEventRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationEventRecordRepository extends JpaRepository<ConversationEventRecord, UUID> {
    List<ConversationEventRecord> findBySessionIdOrderByCreatedAtAsc(UUID sessionRecordId);
}