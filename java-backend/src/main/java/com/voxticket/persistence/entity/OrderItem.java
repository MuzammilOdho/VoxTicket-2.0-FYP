package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Spec §26. Returns and claims (spec §31, §33) operate at this level, not at
 * the order level - a single line item's returnability/final-sale flag must
 * not be inferred from the parent order.
 */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @NotNull
    @Size(max = 255)
    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @NotNull
    @Size(max = 64)
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Positive
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @NotNull
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "returnable", nullable = false)
    private boolean returnable = true;

    @Column(name = "final_sale", nullable = false)
    private boolean finalSale = false;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(String productName, String sku, int quantity, BigDecimal unitPrice, boolean returnable, boolean finalSale) {
        this.productName = productName;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.returnable = returnable;
        this.finalSale = finalSale;
    }

    /** Package-private: only {@link Order#addItem(OrderItem)} should call this, to keep the bidirectional link consistent. */
    void assignOrder(Order order) {
        this.order = order;
    }

    public Order getOrder() {
        return order;
    }

    public String getProductName() {
        return productName;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public boolean isReturnable() {
        return returnable;
    }

    public void setReturnable(boolean returnable) {
        this.returnable = returnable;
    }

    public boolean isFinalSale() {
        return finalSale;
    }

    public void setFinalSale(boolean finalSale) {
        this.finalSale = finalSale;
    }

    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public String toString() {
        return "OrderItem{id=" + getId() + ", sku='" + sku + "', quantity=" + quantity + "}";
    }
}