package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec §30. Table is named {@code returns} (spec §59); the Java class is
 * named {@code ReturnRequest} to avoid colliding with the {@code return}
 * keyword and to make clear this is a request/process, distinct from the
 * {@link Refund} it may eventually produce.
 */
@Entity
@Table(
        name = "returns",
        uniqueConstraints = @UniqueConstraint(name = "uk_returns_return_number", columnNames = "return_number"))
public class ReturnRequest extends BaseEntity {

    @NotNull
    @Size(max = 30)
    @Column(name = "return_number", nullable = false, length = 30)
    private String returnNumber;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReturnStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReturnReason reason;

    @NotNull
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<ReturnItem> items = new ArrayList<>();

    protected ReturnRequest() {
        // JPA
    }

    public ReturnRequest(String returnNumber, Order order, ReturnReason reason) {
        this.returnNumber = returnNumber;
        this.order = order;
        this.reason = reason;
        this.status = ReturnStatus.REQUESTED;
        this.requestedAt = Instant.now();
    }

    public void addItem(ReturnItem item) {
        items.add(item);
        item.assignReturnRequest(this);
    }

    public String getReturnNumber() {
        return returnNumber;
    }

    public Order getOrder() {
        return order;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public void setStatus(ReturnStatus status) {
        this.status = status;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getInspectedAt() {
        return inspectedAt;
    }

    public void setInspectedAt(Instant inspectedAt) {
        this.inspectedAt = inspectedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public List<ReturnItem> getItems() {
        return items;
    }

    @Override
    public String toString() {
        return "ReturnRequest{id=" + getId() + ", returnNumber='" + returnNumber + "', status=" + status + "}";
    }
}