package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VoiceWorkerHeartbeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceWorkerHeartbeatRepository extends JpaRepository<VoiceWorkerHeartbeatEntity, String> {
}
