package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Spec §32. A Refund is a first-class financial entity - never represented
 * as (or substituted by) a {@link SupportTicket}. {@link #returnRequest} is
 * nullable because a refund can originate from a cancellation with no return
 * involved at all.
 */
@Entity
@Table(
        name = "refunds",
        uniqueConstraints = @UniqueConstraint(name = "uk_refunds_refund_number", columnNames = "refund_number"))
public class Refund extends BaseEntity {

    @NotNull
    @Size(max = 30)
    @Column(name = "refund_number", nullable = false, length = 30)
    private String refundNumber;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** Nullable: a cancellation-triggered refund has no associated return. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private ReturnRequest returnRequest;

    @NotNull
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private RefundReason reason;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    /** Simulated provider reference (spec §32: simulate provider status updates when no real provider is connected). */
    @Size(max = 64)
    @Column(name = "provider_reference", length = 64)
    private String providerReference;

    @NotNull
    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    protected Refund() {
        // JPA
    }

    public Refund(
            String refundNumber, Order order, Payment payment, ReturnRequest returnRequest, BigDecimal amount, RefundReason reason) {
        this.refundNumber = refundNumber;
        this.order = order;
        this.payment = payment;
        this.returnRequest = returnRequest;
        this.amount = amount;
        this.reason = reason;
        this.status = RefundStatus.PENDING;
        this.initiatedAt = Instant.now();
    }

    public String getRefundNumber() {
        return refundNumber;
    }

    public Order getOrder() {
        return order;
    }

    public Payment getPayment() {
        return payment;
    }

    public ReturnRequest getReturnRequest() {
        return returnRequest;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundReason getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public void setStatus(RefundStatus status) {
        this.status = status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    @Override
    public String toString() {
        return "Refund{id=" + getId() + ", refundNumber='" + refundNumber + "', status=" + status + ", amount=" + amount + "}";
    }
}