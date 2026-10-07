package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    /**
     * Admin read path (Phase 12/P4): escalation list, optional status
     * filter. Read-only; never used by business logic.
     */
    @Query("select t from SupportTicket t where (:status is null or t.status = :status)")
    Page<SupportTicket> searchAdmin(@Param("status") TicketStatus status, Pageable pageable);

    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    /** IDOR-safe lookup for getMyTicketStatus(ticketReference) - spec §18/§41. */
    Optional<SupportTicket> findByTicketNumberAndCustomerId(String ticketNumber, UUID customerId);

    List<SupportTicket> findByCustomerId(UUID customerId);
}