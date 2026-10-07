package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.VerificationChallenge;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationChallengeRepository extends JpaRepository<VerificationChallenge, UUID> {
    /**
     * Admin read path (Phase 12/P4): optional verified/consumed filters plus
     * session-id substring. Read-only; the OTP hash/salt are never mapped
     * into admin DTOs. Never used by business logic.
     */
    @Query("select c from VerificationChallenge c "
            + "where (:verified is null or c.verified = :verified) "
            + "and (:consumed is null or c.consumed = :consumed) "
            + "and (:sessionId is null or c.sessionId ilike concat('%', cast(:sessionId as string), '%'))")
    Page<VerificationChallenge> searchAdmin(
            @Param("verified") Boolean verified,
            @Param("consumed") Boolean consumed,
            @Param("sessionId") String sessionId,
            Pageable pageable);

    Optional<VerificationChallenge> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant cutoff);
    long countBySessionIdAndCreatedAtAfter(String sessionId, Instant cutoff);
    /** Prior unconsumed challenges for a procedure - invalidated when a new code is issued for it. */
    java.util.List<VerificationChallenge> findByProcedureIdAndConsumedFalse(UUID procedureId);
}
