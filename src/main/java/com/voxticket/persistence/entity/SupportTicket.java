package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.entity.enums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Spec §34. {@link #order} is nullable - not every ticket (e.g. a general complaint) is tied to a specific order. */
@Entity
@Table(
        name = "support_tickets",
        uniqueConstraints = @UniqueConstraint(name = "uk_support_tickets_ticket_number", columnNames = "ticket_number"))
public class SupportTicket extends BaseEntity {

    @NotNull
    @Size(max = 30)
    @Column(name = "ticket_number", nullable = false, length = 30)
    private String ticketNumber;


    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private TicketCategory category;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private TicketPriority priority;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private TicketStatus status;

    @NotNull
    @Size(max = 5000)
    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;


    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SupportTicket() {
        // JPA
    }

    public SupportTicket(
            String ticketNumber, Customer customer, Order order, TicketCategory category, TicketPriority priority, String summary) {
        this.ticketNumber = ticketNumber;
        this.customer = customer;
        this.order = order;
        this.category = category;
        this.priority = priority;
        this.status = TicketStatus.OPEN;
        this.summary = summary;
        this.createdAt = Instant.now();
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Order getOrder() {
        return order;
    }

    public TicketCategory getCategory() {
        return category;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    @Override
    public String toString() {
        return "SupportTicket{id=" + getId() + ", ticketNumber='" + ticketNumber + "', category=" + category
                + ", priority=" + priority + ", status=" + status + "}";
    }
}