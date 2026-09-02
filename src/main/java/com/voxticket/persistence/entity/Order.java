package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec §24. {@link #orderStatus} is intentionally a small, top-level
 * lifecycle (OPEN / CANCELLED / COMPLETED) - shipment, payment, and refund
 * state live on their own entities and must never be inferred from this
 * field alone.
 *
 * <p>{@link #orderNumber} (e.g. "ORD-10001") is the business-facing reference
 * used everywhere outside this persistence layer (AI tools, conversation,
 * support agents). The generated {@code id} (UUID, from {@link BaseEntity})
 * is an internal key only.
 */
@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_orders_order_number", columnNames = "order_number"))
public class Order extends BaseEntity {

    @NotNull
    @Size(max = 30)
    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    private OrderStatus orderStatus = OrderStatus.OPEN;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 25)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.UNFULFILLED;


    @NotNull
    @Size(min = 3, max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @NotNull
    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @NotNull
    @Column(name = "shipping_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingAmount;

    @NotNull
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @NotNull
    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // JPA
    }

    public Order(
            String orderNumber,
            Customer customer,
            String currency,
            BigDecimal subtotal,
            BigDecimal shippingAmount,
            BigDecimal totalAmount,
            Instant placedAt) {
        this.orderNumber = orderNumber;
        this.customer = customer;
        this.currency = currency;
        this.subtotal = subtotal;
        this.shippingAmount = shippingAmount;
        this.totalAmount = totalAmount;
        this.placedAt = placedAt;
        this.orderStatus = OrderStatus.OPEN;
        this.fulfillmentStatus = FulfillmentStatus.UNFULFILLED;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.assignOrder(this);
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public FulfillmentStatus getFulfillmentStatus() {
        return fulfillmentStatus;
    }

    public void setFulfillmentStatus(FulfillmentStatus fulfillmentStatus) {
        this.fulfillmentStatus = fulfillmentStatus;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getShippingAmount() {
        return shippingAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    @Override
    public String toString() {
        return "Order{id=" + getId() + ", orderNumber='" + orderNumber + "', orderStatus=" + orderStatus
                + ", fulfillmentStatus=" + fulfillmentStatus + "}";
    }
}