package com.voxticket.persistence.entity;

import com.voxticket.persistence.entity.enums.ItemCondition;
import com.voxticket.persistence.entity.enums.ReturnReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Spec §31. Partial returns are supported: quantity may be less than the OrderItem's ordered quantity. */
@Entity
@Table(name = "return_items")
public class ReturnItem extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private ReturnRequest returnRequest;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Positive
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private ReturnReason reason;

    /** Null until the item is physically received and inspected. */
    @Enumerated(EnumType.STRING)
    @Column(name = "condition", length = 20)
    private ItemCondition condition;

    protected ReturnItem() {
        // JPA
    }

    public ReturnItem(OrderItem orderItem, int quantity, ReturnReason reason) {
        this.orderItem = orderItem;
        this.quantity = quantity;
        this.reason = reason;
    }

    /** Package-private: only {@link ReturnRequest#addItem(ReturnItem)} should call this. */
    void assignReturnRequest(ReturnRequest returnRequest) {
        this.returnRequest = returnRequest;
    }

    public ReturnRequest getReturnRequest() {
        return returnRequest;
    }

    public OrderItem getOrderItem() {
        return orderItem;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public ItemCondition getCondition() {
        return condition;
    }

    public void setCondition(ItemCondition condition) {
        this.condition = condition;
    }

    @Override
    public String toString() {
        return "ReturnItem{id=" + getId() + ", quantity=" + quantity + ", reason=" + reason + "}";
    }
}