package com.voxticket.persistence.seed;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import java.util.ArrayList;
import java.util.List;

/**
 * The complete deterministic demo dataset, built by {@link SeedDatasetFactory}.
 *
 * <p>This is a pure in-memory object graph: entity instances are fully wired
 * (customer &rarr; order &rarr; items/payments/shipments/returns/refunds/tickets/claims)
 * but nothing is persisted here. {@link DataSeeder} persists the lists in
 * dependency order (customers, orders, payments, shipments, return requests,
 * refunds, tickets, claims). Keeping generation separate from persistence is
 * what makes determinism testable: building the dataset twice must yield
 * byte-identical canonical snapshots without touching a database.
 *
 * <p>Surrogate UUID primary keys are intentionally excluded from the
 * determinism contract: Hibernate assigns them randomly at persist time
 * (see {@code BaseEntity}). Stable business references (order/refund/return/
 * claim/ticket numbers, customer email) are the identity used for diffing.
 */
public final class SeedDataset {

    private final List<Customer> customers = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private final List<Shipment> shipments = new ArrayList<>();
    private final List<ReturnRequest> returnRequests = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<SupportTicket> supportTickets = new ArrayList<>();
    private final List<OrderClaim> orderClaims = new ArrayList<>();

    public List<Customer> getCustomers() {
        return customers;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public List<Payment> getPayments() {
        return payments;
    }

    public List<Shipment> getShipments() {
        return shipments;
    }

    public List<ReturnRequest> getReturnRequests() {
        return returnRequests;
    }

    public List<Refund> getRefunds() {
        return refunds;
    }

    public List<SupportTicket> getSupportTickets() {
        return supportTickets;
    }

    public List<OrderClaim> getOrderClaims() {
        return orderClaims;
    }
}
