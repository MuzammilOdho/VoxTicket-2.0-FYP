package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.SupportTicket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    /** IDOR-safe lookup for getMyTicketStatus(ticketReference) - spec §18/§41. */
    Optional<SupportTicket> findByTicketNumberAndCustomerId(String ticketNumber, UUID customerId);

    List<SupportTicket> findByCustomerId(UUID customerId);
}