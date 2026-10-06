package com.voxticket.service.dto;

import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.entity.enums.TicketStatus;
import java.time.Instant;

public record TicketStatusView(
        String ticketNumber, TicketCategory category, TicketPriority priority, TicketStatus status, String summary, Instant createdAt, Instant resolvedAt) {
}