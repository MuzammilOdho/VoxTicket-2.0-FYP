package com.voxticket.persistence.seed;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
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
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Realistic synthetic demo data (spec §65: no external commerce integration
 * for the FYP - this database is the system of record). Idempotent: if any
 * customers already exist, seeding is skipped so restarting the app never
 * duplicates rows.
 */
@Component
@Profile({"dev", "test"})
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String PKR = "PKR";

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final RefundRepository refundRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final SupportTicketRepository supportTicketRepository;

    public DataSeeder(
            CustomerRepository customerRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            ShipmentRepository shipmentRepository,
            ReturnRequestRepository returnRequestRepository,
            RefundRepository refundRepository,
            OrderClaimRepository orderClaimRepository,
            SupportTicketRepository supportTicketRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.refundRepository = refundRepository;
        this.orderClaimRepository = orderClaimRepository;
        this.supportTicketRepository = supportTicketRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (customerRepository.count() > 0) {
            log.info("Seed data already present - skipping (idempotent seeding).");
            return;
        }
        log.info("Seeding VoxTicket demo data...");

        Customer maria = save(new Customer("Maria", "Khan", "maria.khan@example.pk", "+923001234567"));
        Customer ahmed = save(new Customer("Ahmed", "Raza", "ahmed.raza@example.pk", "+923214567890"));
        Customer sara = save(new Customer("Sara", "Iqbal", "sara.iqbal@example.pk", "+923339876543"));

        seedCodUnfulfilledOrder(maria);
        seedCardPaidUnfulfilledOrder(maria);
        seedAuthorizedNotCapturedOrder(maria);
        seedShippedNotYetDeliveredOrder(maria);
        seedFailedRefundOrder(maria);

        seedDeliveredWithinWindowOrder(ahmed);
        seedDeliveredOutsideWindowOrder(ahmed);
        seedDeliveredFinalSaleOrder(ahmed);
        seedLostShipmentOrder(ahmed);
        seedPartialReturnOrder(ahmed);

        seedAlreadyCancelledOrder(sara);
        seedDamagedClaimOrder(sara);
        seedPendingRefundAfterReturnOrder(sara);
        seedAttemptedDeliveryOrder(sara);

        log.info("Seeding complete: {} customers, {} orders.", customerRepository.count(), orderRepository.count());
    }

    // ---- COD, unfulfilled: cancellable, no financial refund required ----
    private void seedCodUnfulfilledOrder(Customer customer) {
        Order order = newOrder("ORD-10001", customer, bd(4500), bd(200), Instant.now().minus(1, ChronoUnit.DAYS));
        order.addItem(item("Wireless Mouse", "SKU-MOU-01", 1, bd(4500), true, false));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), PKR, PaymentStatus.PENDING));
    }

    // ---- Card paid, unfulfilled: cancellable, refund required if cancelled ----
    private void seedCardPaidUnfulfilledOrder(Customer customer) {
        Order order = newOrder("ORD-10002", customer, bd(12000), bd(0), Instant.now().minus(2, ChronoUnit.DAYS));
        order.addItem(item("Bluetooth Headphones", "SKU-HDP-02", 1, bd(12000), true, false));
        orderRepository.save(order);
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        payment.setCapturedAt(order.getPlacedAt());
    }

    // ---- Card authorized but not captured: cancellable, void (no refund needed) ----
    private void seedAuthorizedNotCapturedOrder(Customer customer) {
        Order order = newOrder("ORD-10003", customer, bd(8500), bd(150), Instant.now().minus(1, ChronoUnit.HOURS));
        order.addItem(item("Laptop Sleeve", "SKU-SLV-03", 1, bd(8500), true, false));
        orderRepository.save(order);
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.AUTHORIZED));
        payment.setAuthorizedAt(order.getPlacedAt());
    }

    // ---- Fulfilled, shipment in transit: NOT cancellable, return not yet possible (undelivered) ----
    private void seedShippedNotYetDeliveredOrder(Customer customer) {
        Order order = newOrder("ORD-10004", customer, bd(15000), bd(300), Instant.now().minus(3, ChronoUnit.DAYS));
        order.addItem(item("Mechanical Keyboard", "SKU-KEY-04", 1, bd(15000), true, false));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        shipmentRepository.save(new Shipment(order, "TCS", "TCS-PK-9001", ShipmentStatus.IN_TRANSIT));
    }

    // ---- A refund that failed (e.g. provider-side failure): "why did my refund fail?" ----
    private void seedFailedRefundOrder(Customer customer) {
        Order order = newOrder("ORD-10005", customer, bd(6000), bd(150), Instant.now().minus(10, ChronoUnit.DAYS));
        order.addItem(item("Phone Case", "SKU-CAS-05", 1, bd(6000), true, false));
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now().minus(8, ChronoUnit.DAYS));
        orderRepository.save(order);
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Refund refund = new Refund("RFN-00001", order, payment, null, order.getTotalAmount(), RefundReason.CANCELLATION);
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailedAt(Instant.now().minus(7, ChronoUnit.DAYS));
        refundRepository.save(refund);
    }

    // ---- Delivered within the 30-day return window, returnable item: eligible for return ----
    private void seedDeliveredWithinWindowOrder(Customer customer) {
        Order order = newOrder("ORD-10006", customer, bd(20000), bd(0), Instant.now().minus(15, ChronoUnit.DAYS));
        order.addItem(item("Running Shoes", "SKU-SHO-06", 1, bd(20000), true, false));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "Leopards", "LEO-PK-3311", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(10, ChronoUnit.DAYS));
    }

    // ---- Delivered 60 days ago: outside the 30-day return window ----
    private void seedDeliveredOutsideWindowOrder(Customer customer) {
        Order order = newOrder("ORD-10007", customer, bd(9000), bd(0), Instant.now().minus(65, ChronoUnit.DAYS));
        order.addItem(item("Desk Lamp", "SKU-LMP-07", 1, bd(9000), true, false));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(60, ChronoUnit.DAYS));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "TCS", "TCS-PK-9002", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(60, ChronoUnit.DAYS));
    }

    // ---- Delivered, but the item is final-sale: not returnable regardless of window ----
    private void seedDeliveredFinalSaleOrder(Customer customer) {
        Order order = newOrder("ORD-10008", customer, bd(3000), bd(0), Instant.now().minus(5, ChronoUnit.DAYS));
        order.addItem(item("Clearance T-Shirt", "SKU-TSH-08", 1, bd(3000), false, true));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(4, ChronoUnit.DAYS));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.WALLET, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "TCS", "TCS-PK-9003", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(4, ChronoUnit.DAYS));
    }

    // ---- Shipment lost in transit: claim/escalation scenario ----
    private void seedLostShipmentOrder(Customer customer) {
        Order order = newOrder("ORD-10009", customer, bd(11000), bd(250), Instant.now().minus(20, ChronoUnit.DAYS));
        order.addItem(item("Coffee Maker", "SKU-COF-09", 1, bd(11000), true, false));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        shipmentRepository.save(new Shipment(order, "M&P", "MNP-PK-7788", ShipmentStatus.LOST));
    }

    // ---- Delivered order with a return already in progress for PART of the quantity ----
    private void seedPartialReturnOrder(Customer customer) {
        Order order = newOrder("ORD-10010", customer, bd(16000), bd(0), Instant.now().minus(12, ChronoUnit.DAYS));
        OrderItem item = item("Cotton Bedsheet Set (pack of 4)", "SKU-BED-10", 4, bd(4000), true, false);
        order.addItem(item);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(11, ChronoUnit.DAYS));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "TCS", "TCS-PK-9004", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(11, ChronoUnit.DAYS));

        ReturnRequest returnRequest = new ReturnRequest("RTN-00001", order, ReturnReason.WRONG_SIZE);
        returnRequest.addItem(new com.voxticket.persistence.entity.ReturnItem(item, 1, ReturnReason.WRONG_SIZE)); // only 1 of 4 returned
        returnRequestRepository.save(returnRequest);
    }

    // ---- Already-cancelled order with a succeeded refund ----
    private void seedAlreadyCancelledOrder(Customer customer) {
        Order order = newOrder("ORD-10011", customer, bd(7000), bd(0), Instant.now().minus(6, ChronoUnit.DAYS));
        order.addItem(item("Table Lamp", "SKU-LMP-11", 1, bd(7000), true, false));
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now().minus(5, ChronoUnit.DAYS));
        orderRepository.save(order);
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.REFUNDED));
        Refund refund = new Refund("RFN-00002", order, payment, null, order.getTotalAmount(), RefundReason.CANCELLATION);
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setCompletedAt(Instant.now().minus(4, ChronoUnit.DAYS));
        refundRepository.save(refund);
    }

    // ---- Delivered order with a damaged-item claim that has generated a support ticket ----
    private void seedDamagedClaimOrder(Customer customer) {
        Order order = newOrder("ORD-10012", customer, bd(18000), bd(0), Instant.now().minus(4, ChronoUnit.DAYS));
        OrderItem orderItem = item("Ceramic Dinner Set", "SKU-DIN-12", 1, bd(18000), true, false);
        order.addItem(orderItem);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "Leopards", "LEO-PK-3312", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(3, ChronoUnit.DAYS));

        SupportTicket ticket = supportTicketRepository.save(new SupportTicket(
                "TCK-00001", customer, order, TicketCategory.CLAIM, TicketPriority.HIGH,
                "Customer reports dinner set arrived with two broken plates."));
        OrderClaim claim = new OrderClaim("CLM-00001", order, orderItem, ClaimReason.DAMAGED, ClaimResolution.REPLACEMENT);
        claim.setStatus(ClaimStatus.IN_REVIEW);
        claim.setSupportTicket(ticket);
        orderClaimRepository.save(claim);
    }

    // ---- Completed return, refund still PENDING: "where is my refund?" follow-up scenario ----
    private void seedPendingRefundAfterReturnOrder(Customer customer) {
        Order order = newOrder("ORD-10013", customer, bd(9500), bd(0), Instant.now().minus(18, ChronoUnit.DAYS));
        OrderItem orderItem = item("Air Fryer", "SKU-AIR-13", 1, bd(9500), true, false);
        order.addItem(orderItem);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.now().minus(17, ChronoUnit.DAYS));
        orderRepository.save(order);
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), PKR, PaymentStatus.PARTIALLY_REFUNDED));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "TCS", "TCS-PK-9005", ShipmentStatus.DELIVERED));
        shipment.setDeliveredAt(Instant.now().minus(17, ChronoUnit.DAYS));

        ReturnRequest returnRequest = new ReturnRequest("RTN-00002", order, ReturnReason.DEFECTIVE);
        returnRequest.addItem(new com.voxticket.persistence.entity.ReturnItem(orderItem, 1, ReturnReason.DEFECTIVE));
        returnRequest.setStatus(ReturnStatus.COMPLETED);
        returnRequest.setReceivedAt(Instant.now().minus(12, ChronoUnit.DAYS));
        returnRequest.setInspectedAt(Instant.now().minus(11, ChronoUnit.DAYS));
        returnRequest.setCompletedAt(Instant.now().minus(11, ChronoUnit.DAYS));
        returnRequestRepository.save(returnRequest);

        refundRepository.save(new Refund("RFN-00003", order, payment, returnRequest, order.getTotalAmount(), RefundReason.RETURN));
    }

    // ---- Attempted delivery: courier tried and could not deliver ----
    private void seedAttemptedDeliveryOrder(Customer customer) {
        Order order = newOrder("ORD-10014", customer, bd(5000), bd(150), Instant.now().minus(2, ChronoUnit.DAYS));
        order.addItem(item("Yoga Mat", "SKU-YOG-14", 1, bd(5000), true, false));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), PKR, PaymentStatus.PENDING));
        shipmentRepository.save(new Shipment(order, "M&P", "MNP-PK-7789", ShipmentStatus.ATTEMPTED_DELIVERY));
    }

    // ---- helpers ----

    private Order newOrder(String orderNumber, Customer customer, BigDecimal subtotal, BigDecimal shipping, Instant placedAt) {
        BigDecimal total = subtotal.add(shipping);
        return new Order(orderNumber, customer, PKR, subtotal, shipping, total, placedAt);
    }

    private OrderItem item(String name, String sku, int qty, BigDecimal unitPrice, boolean returnable, boolean finalSale) {
        return new OrderItem(name, sku, qty, unitPrice, returnable, finalSale);
    }

    private BigDecimal bd(long value) {
        return BigDecimal.valueOf(value).setScale(2);
    }

    private Customer save(Customer customer) {
        return customerRepository.save(customer);
    }
}