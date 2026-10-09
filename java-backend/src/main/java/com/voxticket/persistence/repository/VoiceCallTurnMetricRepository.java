package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceCallTurnMetricEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceCallTurnMetricRepository extends JpaRepository<VoiceCallTurnMetricEntity, UUID> {
    List<VoiceCallTurnMetricEntity> findByCallIdOrderByTurnNumberAsc(UUID callId);
    long countByCallId(UUID callId);
}
