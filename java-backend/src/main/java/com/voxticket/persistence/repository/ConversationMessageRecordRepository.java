package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ConversationMessageRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMessageRecordRepository extends JpaRepository<ConversationMessageRecord, UUID> {
    List<ConversationMessageRecord> findBySessionIdOrderByTurnNumberAsc(UUID sessionRecordId);
}