package com.voxticket.persistence.seed;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.entity.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

/**
 * Builds the entire VoxTicket demo dataset deterministically.
 *
 * <p>Determinism contract (Phase 3):
 * <ul>
 *   <li><b>Fixed time anchor</b> - every timestamp in the dataset is derived
 *       from {@link #ANCHOR} ({@code 2026-09-01T00:00:00Z}). Nothing reads the
 *       wall clock: no {@code Instant.now()}, no "days ago from today". The
 *       anchor sits weeks in the past relative to any real run, so a timestamp
 *       leak from the wall clock is trivially detectable (it would land near
 *       real now, far from every anchor-relative value) and is asserted
 *       against in tests.</li>
 *   <li><b>Fixed random seed</b> - cosmetic variety (product picks, price
 *       jitter, phone suffixes) comes from a single {@link Random} seeded
 *       with {@link #RANDOM_SEED}. {@code java.util.Random} is specified to be
 *       reproducible for a fixed seed, so the dataset is identical on every
 *       JVM and every run.</li>
 *   <li><b>Stable business identity</b> - order/refund/return/claim/ticket
 *       numbers and customer emails are allocated from monotonic counters in
 *       a fixed scenario order. Surrogate UUID primary keys are assigned by
 *       Hibernate at persist time and are deliberately excluded from the
 *       determinism contract.</li>
 * </ul>
 *
 * <p>Coverage map - every value of the lifecycle enums appears at least once,
 * so each conversation path has demo data behind it:
 * <ul>
 *   <li>Shipments: LABEL_CREATED, IN_TRANSIT, OUT_FOR_DELIVERY,
 *       ATTEMPTED_DELIVERY, DELIVERED, EXCEPTION, LOST</li>
 *   <li>Returns: REQUESTED, APPROVED, IN_TRANSIT, RECEIVED, INSPECTED,
 *       COMPLETED, REJECTED, CANCELLED</li>
 *   <li>Claims: OPEN, IN_REVIEW, RESOLVED, REJECTED (plus the V7 edge case:
 *       a REJECTED claim followed by a new OPEN claim on the same item)</li>
 *   <li>Refunds: PENDING, SUCCEEDED, FAILED</li>
 *   <li>Payments: PENDING, AUTHORIZED, PAID, FAILED, VOIDED,
 *       PARTIALLY_REFUNDED, REFUNDED</li>
 *   <li>Tickets: OPEN, IN_PROGRESS, RESOLVED, CLOSED</li>
 *   <li>Orders: OPEN, CANCELLED, COMPLETED; fulfillment UNFULFILLED,
 *       PARTIALLY_FULFILLED, FULFILLED</li>
 * </ul>
 * Edge cases: 30-day return-window boundary (delivered exactly 30 vs 31 days
 * before the anchor), mixed-eligibility multi-item orders (returnable +
 * final-sale + non-returnable), partial-quantity returns, COD-paid-vs-unpaid
 * delivered orders, failed then retried refunds, voided authorizations.
 */
public final class SeedDatasetFactory {

    /** Fixed time anchor: every dataset timestamp is relative to this instant, never to the wall clock. */
    public static final Instant ANCHOR = Instant.parse("2026-09-01T00:00:00Z");

    /** Fixed seed for all cosmetic randomness (product picks, price jitter, phone suffixes). */
    public static final long RANDOM_SEED = 20260930L;

    public static final int EXPECTED_CUSTOMERS = 12;
    public static final int EXPECTED_ORDERS = 54;

    private static final String PKR = "PKR";

    private SeedDatasetFactory() {
    }

    /** Builds the full dataset. Pure function of the constants above: no I/O, no clock, no persistence. */
    public static SeedDataset build() {
        return new Builder().build();
    }

    // ------------------------------------------------------------------
    // Builder: holds the seeded Random and the monotonic number counters.
    // ------------------------------------------------------------------

    private static final class Builder {
        private final Random random = new Random(RANDOM_SEED);
        private final SeedDataset dataset = new SeedDataset();

        private int orderSeq = 10001;
        private int refundSeq = 4; // RFN-00001..00003 are the originals
        private int returnSeq = 3; // RTN-00001..00002 are the originals
        private int claimSeq = 2; // CLM-00001 is the original
        private int ticketSeq = 2; // TCK-00001 is the original
        private int skuSeq = 100;
        private int trackingSeq = 9100;

        // Product catalog for generated orders: name, sku code, base price (PKR).
        private final List<String[]> catalog = List.of(
                new String[]{"USB-C Hub", "HUB", "7500"},
                new String[]{"Electric Kettle", "KET", "6800"},
                new String[]{"Office Chair", "CHR", "32000"},
                new String[]{"Wall Clock", "CLK", "2800"},
                new String[]{"Backpack", "BPK", "5400"},
                new String[]{"Smart Watch", "SWT", "25000"},
                new String[]{"Hair Dryer", "HDR", "7200"},
                new String[]{"LED Strip Lights", "LED", "2200"},
                new String[]{"Water Bottle (1L)", "WTR", "1800"},
                new String[]{"Ceramic Mug Set", "MUG", "2400"},
                new String[]{"Bluetooth Speaker", "SPK", "9800"},
                new String[]{"Notebook Pack (3)", "NBK", "1200"});

        SeedDataset build() {
            Customer maria = customer("Maria", "Khan", "maria.khan@example.pk", 0);
            Customer ahmed = customer("Ahmed", "Raza", "ahmed.raza@example.pk", 1);
            Customer sara = customer("Sara", "Iqbal", "sara.iqbal@example.pk", 2);
            seedOriginals(maria, ahmed, sara);

            seedCancellationMatrix(customer("Bilal", "Sheikh", "bilal.sheikh@example.pk", 3));
            seedReturnWindowEdges(customer("Hina", "Tariq", "hina.tariq@example.pk", 4));
            seedReturnLifecycle(customer("Usman", "Farooq", "usman.farooq@example.pk", 5));
            seedRefundPaths(customer("Ayesha", "Malik", "ayesha.malik@example.pk", 6));
            seedClaimPaths(customer("Daniyal", "Ahmed", "daniyal.ahmed@example.pk", 7));
            seedShipmentTracking(customer("Fatima", "Noor", "fatima.noor@example.pk", 8));
            seedPaymentStates(customer("Imran", "Khalid", "imran.khalid@example.pk", 9));
            seedMultiItemOrders(customer("Nadia", "Hussain", "nadia.hussain@example.pk", 10));
            seedSupportTickets(customer("Omer", "Farooq", "omer.farooq@example.pk", 11));
            return dataset;
        }

        // ---- time helpers: everything relative to ANCHOR ----

        private Instant daysAgo(long days) {
            return ANCHOR.minus(days, ChronoUnit.DAYS);
        }

        private Instant hoursAgo(long hours) {
            return ANCHOR.minus(hours, ChronoUnit.HOURS);
        }

        // ---- entity helpers ----

        private Customer customer(String first, String last, String email, int idx) {
            Customer c = new Customer(first, last, email,
                    "+92300" + String.format("%07d", random.nextInt(10_000_000)));
            c.setCreatedAt(ANCHOR.minus(200 + idx * 17L, ChronoUnit.DAYS));
            dataset.getCustomers().add(c);
            return c;
        }

        private record ItemSpec(String name, String sku, int qty, BigDecimal unitPrice,
                               boolean returnable, boolean finalSale) {
        }

        private ItemSpec spec(String name, String sku, int qty, long unitPrice,
                             boolean returnable, boolean finalSale) {
            return new ItemSpec(name, sku, qty, bd(unitPrice), returnable, finalSale);
        }

        /** Picks a catalog product with deterministic price jitter. */
        private ItemSpec catalogSpec(int qty, boolean returnable, boolean finalSale) {
            String[] entry = catalog.get(random.nextInt(catalog.size()));
            long price = Long.parseLong(entry[2]) + random.nextInt(2000);
            return new ItemSpec(entry[0], "SKU-" + entry[1] + "-" + (skuSeq++),
                    qty, bd(price), returnable, finalSale);
        }

        private Order newOrder(Customer customer, Instant placedAt, long shipping, ItemSpec... specs) {
            BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
            for (ItemSpec s : specs) {
                subtotal = subtotal.add(s.unitPrice().multiply(BigDecimal.valueOf(s.qty())));
            }
            BigDecimal shippingBd = bd(shipping);
            Order order = new Order("ORD-" + (orderSeq++), customer, PKR,
                    subtotal, shippingBd, subtotal.add(shippingBd), placedAt);
            for (ItemSpec s : specs) {
                order.addItem(new OrderItem(s.name(), s.sku(), s.qty(), s.unitPrice(), s.returnable(), s.finalSale()));
            }
            dataset.getOrders().add(order);
            return order;
        }

        private Payment payment(Order order, PaymentMethod method, PaymentStatus status, Instant createdAt) {
            Payment p = new Payment(order, method, order.getTotalAmount(), PKR, status);
            p.setCreatedAt(createdAt);
            dataset.getPayments().add(p);
            return p;
        }

        private Shipment shipment(Order order, String carrier, String carrierCode,
                                 ShipmentStatus status, Instant shippedAt, Instant statusAt) {
            Shipment s = new Shipment(order, carrier, carrierCode + "-PK-" + (trackingSeq++), status);
            if (shippedAt != null) {
                s.setShippedAt(shippedAt);
                s.setEstimatedDeliveryAt(shippedAt.plus(4, ChronoUnit.DAYS));
            }
            if (status == ShipmentStatus.DELIVERED) {
                s.setDeliveredAt(statusAt);
            }
            // setStatus() re-stamps updatedAt with the wall clock, so overwrite last.
            s.setUpdatedAt(statusAt);
            dataset.getShipments().add(s);
            return s;
        }

        private ReturnRequest returnRequest(Order order, ReturnReason reason, Instant requestedAt) {
            ReturnRequest r = new ReturnRequest(nextReturnNumber(), order, reason);
            r.setRequestedAt(requestedAt);
            dataset.getReturnRequests().add(r);
            return r;
        }

        private void returnItem(ReturnRequest request, OrderItem item, int qty, ReturnReason reason) {
            request.addItem(new ReturnItem(item, qty, reason));
        }

        private Refund refund(String number, Order order, Payment payment, ReturnRequest returnRequest,
                              RefundReason reason, Instant initiatedAt) {
            Refund r = new Refund(number, order, payment, returnRequest, order.getTotalAmount(), reason);
            r.setInitiatedAt(initiatedAt);
            dataset.getRefunds().add(r);
            return r;
        }

        private SupportTicket ticket(Customer customer, Order order, TicketCategory category,
                                    TicketPriority priority, TicketStatus status,
                                    String summary, Instant createdAt) {
            SupportTicket t = new SupportTicket(nextTicketNumber(), customer, order, category, priority, summary);
            t.setCreatedAt(createdAt);
            t.setStatus(status);
            dataset.getSupportTickets().add(t);
            return t;
        }

        private OrderClaim claim(Order order, OrderItem item, ClaimReason reason,
                               ClaimResolution resolution, ClaimStatus status, Instant createdAt) {
            OrderClaim c = new OrderClaim(nextClaimNumber(), order, item, reason, resolution);
            c.setCreatedAt(createdAt);
            c.setStatus(status);
            dataset.getOrderClaims().add(c);
            return c;
        }

        private String nextRefundNumber() {
            return "RFN-" + String.format("%05d", refundSeq++);
        }

        private String nextReturnNumber() {
            return "RTN-" + String.format("%05d", returnSeq++);
        }

        private String nextClaimNumber() {
            return "CLM-" + String.format("%05d", claimSeq++);
        }

        private String nextTicketNumber() {
            return "TCK-" + String.format("%05d", ticketSeq++);
        }

        private BigDecimal bd(long value) {
            return BigDecimal.valueOf(value).setScale(2);
        }

        // ------------------------------------------------------------------
        // Original 14 scenarios (kept verbatim in behavior, anchor-relative).
        // ------------------------------------------------------------------

        private void seedOriginals(Customer maria, Customer ahmed, Customer sara) {
            // ORD-10001: COD, unfulfilled - cancellable, no financial refund required.
            Order o1 = newOrder(maria, daysAgo(1), 200,
                    spec("Wireless Mouse", "SKU-MOU-01", 1, 4500, true, false));
            payment(o1, PaymentMethod.COD, PaymentStatus.PENDING, daysAgo(1).plus(30, ChronoUnit.MINUTES));

            // ORD-10002: card paid, unfulfilled - cancellable, refund required if cancelled.
            Order o2 = newOrder(maria, daysAgo(2), 0,
                    spec("Bluetooth Headphones", "SKU-HDP-02", 1, 12000, true, false));
            Payment p2 = payment(o2, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(2).plus(30, ChronoUnit.MINUTES));
            p2.setCapturedAt(daysAgo(2));

            // ORD-10003: card authorized but not captured - cancellable, void (no refund needed).
            Order o3 = newOrder(maria, hoursAgo(1), 150,
                    spec("Laptop Sleeve", "SKU-SLV-03", 1, 8500, true, false));
            Payment p3 = payment(o3, PaymentMethod.CARD, PaymentStatus.AUTHORIZED, hoursAgo(1).plus(5, ChronoUnit.MINUTES));
            p3.setAuthorizedAt(hoursAgo(1));

            // ORD-10004: fulfilled, shipment in transit - NOT cancellable, return not yet possible.
            Order o4 = newOrder(maria, daysAgo(3), 300,
                    spec("Mechanical Keyboard", "SKU-KEY-04", 1, 15000, true, false));
            o4.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o4, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(3).plus(30, ChronoUnit.MINUTES));
            shipment(o4, "TCS", "TCS", ShipmentStatus.IN_TRANSIT, daysAgo(2), daysAgo(1));

            // ORD-10005: a refund that failed - "why did my refund fail?"
            Order o5 = newOrder(maria, daysAgo(10), 150,
                    spec("Phone Case", "SKU-CAS-05", 1, 6000, true, false));
            o5.setOrderStatus(OrderStatus.CANCELLED);
            o5.setCancelledAt(daysAgo(8));
            Payment p5 = payment(o5, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(10).plus(30, ChronoUnit.MINUTES));
            Refund r1 = refund("RFN-00001", o5, p5, null, RefundReason.CANCELLATION, daysAgo(8).plus(2, ChronoUnit.HOURS));
            r1.setStatus(RefundStatus.FAILED);
            r1.setFailedAt(daysAgo(7));

            // ORD-10006: delivered within the 30-day return window - eligible for return.
            Order o6 = newOrder(ahmed, daysAgo(15), 0,
                    spec("Running Shoes", "SKU-SHO-06", 1, 20000, true, false));
            o6.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o6.setOrderStatus(OrderStatus.COMPLETED);
            o6.setCompletedAt(daysAgo(10));
            payment(o6, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(15).plus(30, ChronoUnit.MINUTES));
            shipment(o6, "Leopards", "LEO", ShipmentStatus.DELIVERED, daysAgo(14), daysAgo(10));

            // ORD-10007: delivered 60 days ago - outside the 30-day return window.
            Order o7 = newOrder(ahmed, daysAgo(65), 0,
                    spec("Desk Lamp", "SKU-LMP-07", 1, 9000, true, false));
            o7.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o7.setOrderStatus(OrderStatus.COMPLETED);
            o7.setCompletedAt(daysAgo(60));
            payment(o7, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(65).plus(30, ChronoUnit.MINUTES));
            shipment(o7, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(64), daysAgo(60));

            // ORD-10008: delivered, but the item is final-sale - not returnable regardless of window.
            Order o8 = newOrder(ahmed, daysAgo(5), 0,
                    spec("Clearance T-Shirt", "SKU-TSH-08", 1, 3000, false, true));
            o8.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o8.setOrderStatus(OrderStatus.COMPLETED);
            o8.setCompletedAt(daysAgo(4));
            payment(o8, PaymentMethod.WALLET, PaymentStatus.PAID, daysAgo(5).plus(30, ChronoUnit.MINUTES));
            shipment(o8, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(5), daysAgo(4));

            // ORD-10009: shipment lost in transit - claim/escalation scenario.
            Order o9 = newOrder(ahmed, daysAgo(20), 250,
                    spec("Coffee Maker", "SKU-COF-09", 1, 11000, true, false));
            o9.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o9, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(20).plus(30, ChronoUnit.MINUTES));
            shipment(o9, "M&P", "MNP", ShipmentStatus.LOST, daysAgo(19), daysAgo(12));

            // ORD-10010: delivered order with a return already in progress for PART of the quantity.
            Order o10 = newOrder(ahmed, daysAgo(12), 0,
                    spec("Cotton Bedsheet Set (pack of 4)", "SKU-BED-10", 4, 4000, true, false));
            o10.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o10.setOrderStatus(OrderStatus.COMPLETED);
            o10.setCompletedAt(daysAgo(11));
            payment(o10, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(12).plus(30, ChronoUnit.MINUTES));
            shipment(o10, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(12), daysAgo(11));
            ReturnRequest rr1 = returnRequest(o10, ReturnReason.WRONG_SIZE, daysAgo(9));
            returnItem(rr1, o10.getItems().get(0), 1, ReturnReason.WRONG_SIZE);
            rr1.setStatus(ReturnStatus.IN_TRANSIT);
            rr1.setApprovedAt(daysAgo(8));

            // ORD-10011: already-cancelled order with a succeeded refund.
            Order o11 = newOrder(sara, daysAgo(6), 0,
                    spec("Table Lamp", "SKU-LMP-11", 1, 7000, true, false));
            o11.setOrderStatus(OrderStatus.CANCELLED);
            o11.setCancelledAt(daysAgo(5));
            Payment p11 = payment(o11, PaymentMethod.CARD, PaymentStatus.REFUNDED, daysAgo(6).plus(30, ChronoUnit.MINUTES));
            Refund r2 = refund("RFN-00002", o11, p11, null, RefundReason.CANCELLATION, daysAgo(5).plus(1, ChronoUnit.HOURS));
            r2.setStatus(RefundStatus.SUCCEEDED);
            r2.setCompletedAt(daysAgo(4));

            // ORD-10012: delivered order with a damaged-item claim that generated a support ticket.
            Order o12 = newOrder(sara, daysAgo(4), 0,
                    spec("Ceramic Dinner Set", "SKU-DIN-12", 1, 18000, true, false));
            o12.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o12.setOrderStatus(OrderStatus.COMPLETED);
            o12.setCompletedAt(daysAgo(3));
            payment(o12, PaymentMethod.CARD, PaymentStatus.PAID, daysAgo(4).plus(30, ChronoUnit.MINUTES));
            shipment(o12, "Leopards", "LEO", ShipmentStatus.DELIVERED, daysAgo(4), daysAgo(3));
            SupportTicket t1 = ticket(sara, o12, TicketCategory.CLAIM, TicketPriority.HIGH,
                    TicketStatus.OPEN, "Customer reports dinner set arrived with two broken plates.", daysAgo(2));
            OrderClaim c1 = claim(o12, o12.getItems().get(0), ClaimReason.DAMAGED,
                    ClaimResolution.REPLACEMENT, ClaimStatus.IN_REVIEW, daysAgo(2));
            c1.setSupportTicket(t1);

            // ORD-10013: completed return, refund still PENDING - "where is my refund?"
            Order o13 = newOrder(sara, daysAgo(18), 0,
                    spec("Air Fryer", "SKU-AIR-13", 1, 9500, true, false));
            o13.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o13.setOrderStatus(OrderStatus.COMPLETED);
            o13.setCompletedAt(daysAgo(17));
            Payment p13 = payment(o13, PaymentMethod.CARD, PaymentStatus.PARTIALLY_REFUNDED,
                    daysAgo(18).plus(30, ChronoUnit.MINUTES));
            shipment(o13, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(18), daysAgo(17));
            ReturnRequest rr2 = returnRequest(o13, ReturnReason.DEFECTIVE, daysAgo(14));
            returnItem(rr2, o13.getItems().get(0), 1, ReturnReason.DEFECTIVE);
            rr2.setStatus(ReturnStatus.COMPLETED);
            rr2.setApprovedAt(daysAgo(13));
            rr2.setReceivedAt(daysAgo(12));
            rr2.setInspectedAt(daysAgo(11));
            rr2.setCompletedAt(daysAgo(11));
            refund("RFN-00003", o13, p13, rr2, RefundReason.RETURN, daysAgo(11).plus(2, ChronoUnit.HOURS));

            // ORD-10014: attempted delivery - courier tried and could not deliver.
            Order o14 = newOrder(sara, daysAgo(2), 150,
                    spec("Yoga Mat", "SKU-YOG-14", 1, 5000, true, false));
            o14.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o14, PaymentMethod.COD, PaymentStatus.PENDING, daysAgo(2).plus(30, ChronoUnit.MINUTES));
            shipment(o14, "M&P", "MNP", ShipmentStatus.ATTEMPTED_DELIVERY, daysAgo(1), hoursAgo(20));
        }

        // ------------------------------------------------------------------
        // New scenario families: full lifecycle and edge-case coverage.
        // ------------------------------------------------------------------

        /** Cancellation matrix: every cancel outcome (void / refund / refused / already-cancelled). */
        private void seedCancellationMatrix(Customer customer) {
            // COD unfulfilled: cancellable, no money captured, nothing to refund.
            Order o15 = newOrder(customer, daysAgo(1), 200, catalogSpec(1, true, false));
            payment(o15, PaymentMethod.COD, PaymentStatus.PENDING, o15.getPlacedAt().plus(30, ChronoUnit.MINUTES));

            // Wallet-paid unfulfilled: cancellable, refund required on cancel.
            Order o16 = newOrder(customer, daysAgo(2), 0, catalogSpec(1, true, false));
            Payment p16 = payment(o16, PaymentMethod.WALLET, PaymentStatus.PAID,
                    o16.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            p16.setCapturedAt(daysAgo(2));

            // Card authorized 3h ago, never captured: cancel voids the authorization.
            Order o17 = newOrder(customer, hoursAgo(3), 150, catalogSpec(1, true, false));
            Payment p17 = payment(o17, PaymentMethod.CARD, PaymentStatus.AUTHORIZED,
                    o17.getPlacedAt().plus(5, ChronoUnit.MINUTES));
            p17.setAuthorizedAt(hoursAgo(3));

            // Out for delivery: cancellation must be refused.
            Order o18 = newOrder(customer, daysAgo(1), 250, catalogSpec(1, true, false));
            o18.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o18, PaymentMethod.CARD, PaymentStatus.PAID, o18.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o18, "TCS", "TCS", ShipmentStatus.OUT_FOR_DELIVERY, daysAgo(1), hoursAgo(2));

            // Cancelled 25 days ago with a succeeded refund: repeat cancel must be idempotent.
            Order o19 = newOrder(customer, daysAgo(26), 0, catalogSpec(1, true, false));
            o19.setOrderStatus(OrderStatus.CANCELLED);
            o19.setCancelledAt(daysAgo(25));
            Payment p19 = payment(o19, PaymentMethod.CARD, PaymentStatus.REFUNDED,
                    o19.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            Refund r4 = refund(nextRefundNumber(), o19, p19, null, RefundReason.CANCELLATION,
                    daysAgo(25).plus(1, ChronoUnit.HOURS));
            r4.setStatus(RefundStatus.SUCCEEDED);
            r4.setCompletedAt(daysAgo(24));
        }

        /** Return-window edges: exact 30-day boundary, mixed eligibility, unpaid COD delivered. */
        private void seedReturnWindowEdges(Customer customer) {
            // Delivered EXACTLY 30 days before the anchor: the boundary case.
            Order o20 = newOrder(customer, daysAgo(32), 0, catalogSpec(1, true, false));
            o20.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o20.setOrderStatus(OrderStatus.COMPLETED);
            o20.setCompletedAt(daysAgo(30));
            payment(o20, PaymentMethod.CARD, PaymentStatus.PAID, o20.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o20, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(31), daysAgo(30));

            // Delivered 31 days before the anchor: just outside the window.
            Order o21 = newOrder(customer, daysAgo(33), 0, catalogSpec(1, true, false));
            o21.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o21.setOrderStatus(OrderStatus.COMPLETED);
            o21.setCompletedAt(daysAgo(31));
            payment(o21, PaymentMethod.CARD, PaymentStatus.PAID, o21.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o21, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(32), daysAgo(31));

            // One order, three eligibility outcomes: returnable, final-sale, non-returnable flag.
            Order o22 = newOrder(customer, daysAgo(6), 150,
                    catalogSpec(1, true, false),
                    catalogSpec(1, false, true),
                    catalogSpec(1, false, false));
            o22.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o22.setOrderStatus(OrderStatus.COMPLETED);
            o22.setCompletedAt(daysAgo(5));
            payment(o22, PaymentMethod.CARD, PaymentStatus.PAID, o22.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o22, "Leopards", "LEO", ShipmentStatus.DELIVERED, daysAgo(6), daysAgo(5));

            // Delivered but COD still unpaid: returnable goods, collection pending.
            Order o23 = newOrder(customer, daysAgo(3), 150, catalogSpec(1, true, false));
            o23.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o23.setOrderStatus(OrderStatus.COMPLETED);
            o23.setCompletedAt(daysAgo(2));
            payment(o23, PaymentMethod.COD, PaymentStatus.PENDING, o23.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o23, "M&P", "MNP", ShipmentStatus.DELIVERED, daysAgo(3), daysAgo(2));

            // Label created, courier has not picked up yet.
            Order o24 = newOrder(customer, daysAgo(1), 200, catalogSpec(1, true, false));
            payment(o24, PaymentMethod.CARD, PaymentStatus.PAID, o24.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o24, "PostEx", "PEX", ShipmentStatus.LABEL_CREATED, null, daysAgo(1).plus(1, ChronoUnit.HOURS));
        }

        /** One order per return lifecycle state, with a matching timestamp trail. */
        private void seedReturnLifecycle(Customer customer) {
            seedReturnAt(customer, ReturnStatus.REQUESTED, daysAgo(1), null, null, null, null);
            seedReturnAt(customer, ReturnStatus.APPROVED, daysAgo(4), daysAgo(3), null, null, null);
            seedReturnAt(customer, ReturnStatus.IN_TRANSIT, daysAgo(5), daysAgo(4), null, null, null);
            seedReturnAt(customer, ReturnStatus.RECEIVED, daysAgo(6), daysAgo(5), daysAgo(3), null, null);
            seedReturnAt(customer, ReturnStatus.INSPECTED, daysAgo(7), daysAgo(6), daysAgo(5), daysAgo(4), null);

            // COMPLETED return with the refund still pending: payment is partially refunded.
            Order done = seedReturnAt(customer, ReturnStatus.COMPLETED,
                    daysAgo(9), daysAgo(8), daysAgo(7), daysAgo(6), daysAgo(5));
            Payment pd = dataset.getPayments().stream()
                    .filter(p -> p.getOrder().equals(done)).findFirst().orElseThrow();
            pd.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            ReturnRequest rr = dataset.getReturnRequests().get(dataset.getReturnRequests().size() - 1);
            refund(nextRefundNumber(), done, pd, rr, RefundReason.RETURN, daysAgo(5).plus(2, ChronoUnit.HOURS));

            seedReturnAt(customer, ReturnStatus.REJECTED, daysAgo(4), null, null, null, null);
            seedReturnAt(customer, ReturnStatus.CANCELLED, daysAgo(3), null, null, null, null);
        }

        private Order seedReturnAt(Customer customer, ReturnStatus status, Instant requestedAt,
                                  Instant approvedAt, Instant receivedAt, Instant inspectedAt, Instant completedAt) {
            Order order = newOrder(customer, daysAgo(14), 0, catalogSpec(1, true, false));
            order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            order.setOrderStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(daysAgo(12));
            payment(order, PaymentMethod.CARD, PaymentStatus.PAID, order.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(order, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(13), daysAgo(12));
            ReturnRequest rr = returnRequest(order, ReturnReason.CHANGED_MIND, requestedAt);
            returnItem(rr, order.getItems().get(0), 1, ReturnReason.CHANGED_MIND);
            rr.setStatus(status);
            if (approvedAt != null) {
                rr.setApprovedAt(approvedAt);
            }
            if (receivedAt != null) {
                rr.setReceivedAt(receivedAt);
            }
            if (inspectedAt != null) {
                rr.setInspectedAt(inspectedAt);
            }
            if (completedAt != null) {
                rr.setCompletedAt(completedAt);
            }
            return order;
        }

        /** Every refund outcome, from cancellation and from returns. */
        private void seedRefundPaths(Customer customer) {
            // Cancelled 9 days ago, refund initiated but still pending.
            Order o33 = newOrder(customer, daysAgo(10), 0, catalogSpec(1, true, false));
            o33.setOrderStatus(OrderStatus.CANCELLED);
            o33.setCancelledAt(daysAgo(9));
            Payment p33 = payment(o33, PaymentMethod.CARD, PaymentStatus.PAID,
                    o33.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            p33.setCapturedAt(daysAgo(10));
            refund(nextRefundNumber(), o33, p33, null, RefundReason.CANCELLATION, daysAgo(8));

            // Cancelled, refund failed: the retry path.
            Order o34 = newOrder(customer, daysAgo(15), 150, catalogSpec(1, true, false));
            o34.setOrderStatus(OrderStatus.CANCELLED);
            o34.setCancelledAt(daysAgo(14));
            Payment p34 = payment(o34, PaymentMethod.CARD, PaymentStatus.PAID,
                    o34.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            p34.setCapturedAt(daysAgo(15));
            Refund r7 = refund(nextRefundNumber(), o34, p34, null, RefundReason.CANCELLATION, daysAgo(13));
            r7.setStatus(RefundStatus.FAILED);
            r7.setFailedAt(daysAgo(12));

            // Cancelled a month ago, refund succeeded long ago.
            Order o35 = newOrder(customer, daysAgo(32), 0, catalogSpec(1, true, false));
            o35.setOrderStatus(OrderStatus.CANCELLED);
            o35.setCancelledAt(daysAgo(30));
            Payment p35 = payment(o35, PaymentMethod.CARD, PaymentStatus.REFUNDED,
                    o35.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            Refund r8 = refund(nextRefundNumber(), o35, p35, null, RefundReason.CANCELLATION, daysAgo(29));
            r8.setStatus(RefundStatus.SUCCEEDED);
            r8.setCompletedAt(daysAgo(28));

            // Delivered, returned, refund fully succeeded: payment shows REFUNDED.
            Order o36 = newOrder(customer, daysAgo(22), 0, catalogSpec(1, true, false));
            o36.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o36.setOrderStatus(OrderStatus.COMPLETED);
            o36.setCompletedAt(daysAgo(20));
            Payment p36 = payment(o36, PaymentMethod.CARD, PaymentStatus.REFUNDED,
                    o36.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o36, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(21), daysAgo(20));
            ReturnRequest rr = returnRequest(o36, ReturnReason.DEFECTIVE, daysAgo(14));
            returnItem(rr, o36.getItems().get(0), 1, ReturnReason.DEFECTIVE);
            rr.setStatus(ReturnStatus.COMPLETED);
            rr.setApprovedAt(daysAgo(13));
            rr.setReceivedAt(daysAgo(12));
            rr.setInspectedAt(daysAgo(11));
            rr.setCompletedAt(daysAgo(10));
            Refund r9 = refund(nextRefundNumber(), o36, p36, rr, RefundReason.RETURN, daysAgo(10).plus(1, ChronoUnit.HOURS));
            r9.setStatus(RefundStatus.SUCCEEDED);
            r9.setCompletedAt(daysAgo(9));
        }

        /** Every claim state, every claim reason family, and the V7 refile edge case. */
        private void seedClaimPaths(Customer customer) {
            // OPEN claim: damaged, replacement requested.
            Order o37 = newOrder(customer, daysAgo(9), 0, catalogSpec(1, true, false));
            fulfillDelivered(o37, 7);
            claim(o37, o37.getItems().get(0), ClaimReason.DAMAGED,
                    ClaimResolution.REPLACEMENT, ClaimStatus.OPEN, daysAgo(2));

            // IN_REVIEW claim for a wrong item, linked to a support ticket.
            Order o38 = newOrder(customer, daysAgo(10), 0, catalogSpec(1, true, false));
            fulfillDelivered(o38, 8);
            SupportTicket t2 = ticket(customer, o38, TicketCategory.CLAIM, TicketPriority.HIGH,
                    TicketStatus.OPEN, "Customer received the wrong variant; claims the ordered color.", daysAgo(3));
            OrderClaim c3 = claim(o38, o38.getItems().get(0), ClaimReason.WRONG_ITEM,
                    ClaimResolution.REFUND, ClaimStatus.IN_REVIEW, daysAgo(3));
            c3.setSupportTicket(t2);

            // RESOLVED claim: defective item refunded; the item may be claimed again later.
            Order o39 = newOrder(customer, daysAgo(42), 0, catalogSpec(1, true, false));
            fulfillDelivered(o39, 40);
            claim(o39, o39.getItems().get(0), ClaimReason.DEFECTIVE,
                    ClaimResolution.REFUND, ClaimStatus.RESOLVED, daysAgo(35));

            // V7 edge: a REJECTED claim does NOT block a fresh OPEN claim on the same item.
            Order o40 = newOrder(customer, daysAgo(24), 0, catalogSpec(1, true, false));
            fulfillDelivered(o40, 22);
            claim(o40, o40.getItems().get(0), ClaimReason.OTHER,
                    ClaimResolution.MANUAL_REVIEW, ClaimStatus.REJECTED, daysAgo(15));
            claim(o40, o40.getItems().get(0), ClaimReason.MISSING_ITEM,
                    ClaimResolution.REPLACEMENT, ClaimStatus.OPEN, daysAgo(2));

            // Lost shipment with an open missing-item claim and an urgent ticket.
            Order o41 = newOrder(customer, daysAgo(16), 250, catalogSpec(1, true, false));
            o41.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o41, PaymentMethod.CARD, PaymentStatus.PAID, o41.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o41, "M&P", "MNP", ShipmentStatus.LOST, daysAgo(15), daysAgo(10));
            SupportTicket t3 = ticket(customer, o41, TicketCategory.CLAIM, TicketPriority.URGENT,
                    TicketStatus.OPEN, "Parcel lost in transit; customer asks for a refund.", daysAgo(9));
            OrderClaim c7 = claim(o41, o41.getItems().get(0), ClaimReason.MISSING_ITEM,
                    ClaimResolution.REFUND, ClaimStatus.OPEN, daysAgo(9));
            c7.setSupportTicket(t3);

            // Multi-item order, claim on exactly one line item.
            Order o42 = newOrder(customer, daysAgo(7), 150,
                    catalogSpec(1, true, false),
                    catalogSpec(1, true, false),
                    catalogSpec(1, true, false));
            fulfillDelivered(o42, 6);
            claim(o42, o42.getItems().get(1), ClaimReason.DAMAGED,
                    ClaimResolution.MANUAL_REVIEW, ClaimStatus.IN_REVIEW, daysAgo(2));
        }

        private void fulfillDelivered(Order order, long daysAgoDelivered) {
            order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            order.setOrderStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(daysAgo(daysAgoDelivered));
            payment(order, PaymentMethod.CARD, PaymentStatus.PAID, order.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(order, "TCS", "TCS", ShipmentStatus.DELIVERED,
                    daysAgo(daysAgoDelivered + 1), daysAgo(daysAgoDelivered));
        }

        /** Remaining shipment states: label created, out for delivery, exception, in transit. */
        private void seedShipmentTracking(Customer customer) {
            Order o43 = newOrder(customer, hoursAgo(6), 150, catalogSpec(1, true, false));
            payment(o43, PaymentMethod.CARD, PaymentStatus.PAID, o43.getPlacedAt().plus(10, ChronoUnit.MINUTES));
            shipment(o43, "PostEx", "PEX", ShipmentStatus.LABEL_CREATED, null, hoursAgo(5));

            Order o44 = newOrder(customer, daysAgo(2), 0, catalogSpec(1, true, false));
            o44.setFulfillmentStatus(FulfillmentStatus.PARTIALLY_FULFILLED);
            payment(o44, PaymentMethod.CARD, PaymentStatus.PAID, o44.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o44, "CallCourier", "CC", ShipmentStatus.OUT_FOR_DELIVERY, daysAgo(2), hoursAgo(3));

            Order o45 = newOrder(customer, daysAgo(6), 200, catalogSpec(1, true, false));
            o45.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o45, PaymentMethod.CARD, PaymentStatus.PAID, o45.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o45, "TCS", "TCS", ShipmentStatus.EXCEPTION, daysAgo(5), daysAgo(1));

            Order o46 = newOrder(customer, daysAgo(4), 150, catalogSpec(1, true, false));
            o46.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o46, PaymentMethod.CARD, PaymentStatus.PAID, o46.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o46, "Leopards", "LEO", ShipmentStatus.IN_TRANSIT, daysAgo(4), daysAgo(1));
        }

        /** Payment outcomes that are not the happy path: failed, voided, partially refunded. */
        private void seedPaymentStates(Customer customer) {
            // Card payment failed at checkout: order stays open, nothing captured.
            Order o47 = newOrder(customer, daysAgo(1), 150, catalogSpec(1, true, false));
            payment(o47, PaymentMethod.CARD, PaymentStatus.FAILED, o47.getPlacedAt().plus(10, ChronoUnit.MINUTES));

            // Authorized then voided when the order was cancelled before capture.
            Order o48 = newOrder(customer, daysAgo(4), 0, catalogSpec(1, true, false));
            o48.setOrderStatus(OrderStatus.CANCELLED);
            o48.setCancelledAt(daysAgo(2));
            Payment p48 = payment(o48, PaymentMethod.CARD, PaymentStatus.VOIDED,
                    o48.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            p48.setAuthorizedAt(daysAgo(4));

            // Partial return completed and its partial refund succeeded.
            Order o49 = newOrder(customer, daysAgo(27), 0, catalogSpec(2, true, false));
            o49.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o49.setOrderStatus(OrderStatus.COMPLETED);
            o49.setCompletedAt(daysAgo(25));
            Payment p49 = payment(o49, PaymentMethod.CARD, PaymentStatus.PARTIALLY_REFUNDED,
                    o49.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o49, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(26), daysAgo(25));
            ReturnRequest rr = returnRequest(o49, ReturnReason.NOT_AS_DESCRIBED, daysAgo(20));
            returnItem(rr, o49.getItems().get(0), 1, ReturnReason.NOT_AS_DESCRIBED);
            rr.setStatus(ReturnStatus.COMPLETED);
            rr.setApprovedAt(daysAgo(19));
            rr.setReceivedAt(daysAgo(18));
            rr.setInspectedAt(daysAgo(17));
            rr.setCompletedAt(daysAgo(16));
            Refund r10 = refund(nextRefundNumber(), o49, p49, rr, RefundReason.RETURN,
                    daysAgo(16).plus(1, ChronoUnit.HOURS));
            r10.setStatus(RefundStatus.SUCCEEDED);
            r10.setCompletedAt(daysAgo(15));
        }

        /** Multi-item and multi-quantity orders with partial returns. */
        private void seedMultiItemOrders(Customer customer) {
            // Five lines: three returnable, one final-sale, one non-returnable flag.
            Order o51 = newOrder(customer, daysAgo(8), 300,
                    catalogSpec(1, true, false),
                    catalogSpec(2, true, false),
                    catalogSpec(1, true, false),
                    catalogSpec(1, false, true),
                    catalogSpec(1, false, false));
            o51.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o51.setOrderStatus(OrderStatus.COMPLETED);
            o51.setCompletedAt(daysAgo(6));
            payment(o51, PaymentMethod.CARD, PaymentStatus.PAID, o51.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o51, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(7), daysAgo(6));

            // Five units bought, two on their way back.
            Order o52 = newOrder(customer, daysAgo(10), 0, catalogSpec(5, true, false));
            o52.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o52.setOrderStatus(OrderStatus.COMPLETED);
            o52.setCompletedAt(daysAgo(8));
            payment(o52, PaymentMethod.CARD, PaymentStatus.PAID, o52.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o52, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(9), daysAgo(8));
            ReturnRequest rr52 = returnRequest(o52, ReturnReason.WRONG_SIZE, daysAgo(3));
            returnItem(rr52, o52.getItems().get(0), 2, ReturnReason.WRONG_SIZE);
            rr52.setStatus(ReturnStatus.IN_TRANSIT);
            rr52.setApprovedAt(daysAgo(2));

            // Three units bought, all three returned, refunded in full.
            Order o53 = newOrder(customer, daysAgo(18), 0, catalogSpec(3, true, false));
            o53.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o53.setOrderStatus(OrderStatus.COMPLETED);
            o53.setCompletedAt(daysAgo(16));
            Payment p53 = payment(o53, PaymentMethod.CARD, PaymentStatus.REFUNDED,
                    o53.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o53, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(17), daysAgo(16));
            ReturnRequest rr53 = returnRequest(o53, ReturnReason.DEFECTIVE, daysAgo(12));
            returnItem(rr53, o53.getItems().get(0), 3, ReturnReason.DEFECTIVE);
            rr53.setStatus(ReturnStatus.COMPLETED);
            rr53.setApprovedAt(daysAgo(11));
            rr53.setReceivedAt(daysAgo(10));
            rr53.setInspectedAt(daysAgo(9));
            rr53.setCompletedAt(daysAgo(8));
            Refund r11 = refund(nextRefundNumber(), o53, p53, rr53, RefundReason.RETURN,
                    daysAgo(8).plus(1, ChronoUnit.HOURS));
            r11.setStatus(RefundStatus.SUCCEEDED);
            r11.setCompletedAt(daysAgo(7));
        }

        /** Support tickets across every status and several categories. */
        private void seedSupportTickets(Customer customer) {
            Order o54 = newOrder(customer, daysAgo(4), 200, catalogSpec(1, true, false));
            o54.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            payment(o54, PaymentMethod.CARD, PaymentStatus.PAID, o54.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o54, "TCS", "TCS", ShipmentStatus.IN_TRANSIT, daysAgo(3), daysAgo(1));
            ticket(customer, o54, TicketCategory.DELIVERY, TicketPriority.HIGH, TicketStatus.IN_PROGRESS,
                    "Rider marked the parcel delivered but it never arrived; investigating with TCS.", daysAgo(2));

            Order o55 = newOrder(customer, daysAgo(12), 0, catalogSpec(1, true, false));
            o55.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
            o55.setOrderStatus(OrderStatus.COMPLETED);
            o55.setCompletedAt(daysAgo(11));
            payment(o55, PaymentMethod.CARD, PaymentStatus.PAID, o55.getPlacedAt().plus(30, ChronoUnit.MINUTES));
            shipment(o55, "TCS", "TCS", ShipmentStatus.DELIVERED, daysAgo(12), daysAgo(11));
            ticket(customer, o55, TicketCategory.PAYMENT, TicketPriority.MEDIUM, TicketStatus.RESOLVED,
                    "Customer was double-charged at checkout; duplicate authorization reversed.", daysAgo(9));
            ticket(customer, o55, TicketCategory.COMPLAINT, TicketPriority.URGENT, TicketStatus.OPEN,
                    "Customer reports rude courier behavior during delivery.", daysAgo(1));
            ticket(customer, o55, TicketCategory.GENERAL, TicketPriority.LOW, TicketStatus.CLOSED,
                    "Customer asked about EMI options; answered and closed.", daysAgo(8));
        }
    }
}
