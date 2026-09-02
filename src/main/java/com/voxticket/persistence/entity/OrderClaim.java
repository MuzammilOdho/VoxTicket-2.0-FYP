package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
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

/** Spec §33. {@link #supportTicket} is nullable/set after the claim triggers ticket creation (spec §34). */
@Entity
@Table(
        name = "order_claims",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_claims_claim_number", columnNames = "claim_number"))
public class OrderClaim extends BaseEntity {

    @NotNull
    @Size(max = 30)
    @Column(name = "claim_number", nullable = false, length = 30)
    private String claimNumber;
    
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private ClaimReason reason;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_resolution", nullable = false, length = 20)
    private ClaimResolution requestedResolution;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "support_ticket_id")
    private SupportTicket supportTicket;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderClaim() {
        // JPA
    }

    public OrderClaim(String claimNumber, Order order, OrderItem orderItem, ClaimReason reason, ClaimResolution requestedResolution) {
        this.claimNumber = claimNumber;
        this.order = order;
        this.orderItem = orderItem;
        this.reason = reason;
        this.requestedResolution = requestedResolution;
        this.status = ClaimStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public Order getOrder() {
        return order;
    }

    public OrderItem getOrderItem() {
        return orderItem;
    }

    public ClaimReason getReason() {
        return reason;
    }

    public ClaimResolution getRequestedResolution() {
        return requestedResolution;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public SupportTicket getSupportTicket() {
        return supportTicket;
    }

    public void setSupportTicket(SupportTicket supportTicket) {
        this.supportTicket = supportTicket;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "OrderClaim{id=" + getId() + ", claimNumber='" + claimNumber + "', reason=" + reason + ", status=" + status + "}";
    }
}