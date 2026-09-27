package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VerificationChallenge;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationChallengeRepository extends JpaRepository<VerificationChallenge, UUID> {
    Optional<VerificationChallenge> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant cutoff);
    long countBySessionIdAndCreatedAtAfter(String sessionId, Instant cutoff);
    /** Prior unconsumed challenges for a procedure - invalidated when a new code is issued for it. */
    java.util.List<VerificationChallenge> findByProcedureIdAndConsumedFalse(UUID procedureId);
}
