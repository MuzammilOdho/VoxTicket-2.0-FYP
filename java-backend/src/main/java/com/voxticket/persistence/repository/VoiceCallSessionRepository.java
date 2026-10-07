package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceCallSessionEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceCallSessionRepository extends JpaRepository<VoiceCallSessionEntity, UUID> {
    Optional<VoiceCallSessionEntity> findByRoom(String room);
    Optional<VoiceCallSessionEntity> findBySessionId(UUID sessionRecordId);
}
