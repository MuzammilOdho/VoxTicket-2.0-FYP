package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2 acceptance test: it must be impossible to retrieve another
 * customer's private data merely by knowing a valid business reference.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class CustomerOrderQueryServiceIdorTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CustomerOrderQueryService queryService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private ReturnRequestRepository returnRequestRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private SupportTicketRepository supportTicketRepository;

    private Customer maria;
    private Customer ahmed;
    private Order mariaOrder;
    private Order ahmedOrder;
    private CustomerIdentity mariaIdentity;

    @BeforeEach
    void setUp() {
        maria = customerRepository.save(new Customer("Maria", "Khan", unique("maria"), "+9230012" + unique4()));
        ahmed = customerRepository.save(new Customer("Ahmed", "Raza", unique("ahmed"), "+9230013" + unique4()));

        mariaOrder = persistOrder("ORD-IDOR-" + shortUnique(), maria);
        ahmedOrder = persistOrder("ORD-IDOR-" + shortUnique(), ahmed);

        Payment ahmedPayment = paymentRepository.save(
                new Payment(ahmedOrder, PaymentMethod.CARD, ahmedOrder.getTotalAmount(), "PKR", PaymentStatus.PAID));
        shipmentRepository.save(new Shipment(ahmedOrder, "TCS", "TCS-IDOR-1", ShipmentStatus.IN_TRANSIT));
        refundRepository.save(
                new Refund("RFN-IDOR-" + shortUnique(), ahmedOrder, ahmedPayment, null, BigDecimal.TEN, RefundReason.GOODWILL));
        ReturnRequest ahmedReturn = new ReturnRequest("RTN-IDOR-" + shortUnique(), ahmedOrder, ReturnReason.OTHER);
        ahmedReturn.addItem(new ReturnItem(ahmedOrder.getItems().get(0), 1, ReturnReason.OTHER));
        returnRequestRepository.save(ahmedReturn);
        supportTicketRepository.save(new SupportTicket(
                "TCK-IDOR-" + shortUnique(), ahmed, ahmedOrder, TicketCategory.GENERAL, TicketPriority.LOW, "test ticket"));

        mariaIdentity = new CustomerIdentity(maria.getId(), IdentityAssurance.PHONE_MATCHED, maria.getPhone());
    }

    private Order persistOrder(String orderNumber, Customer customer) {
        Order order = new Order(
                orderNumber, customer, "PKR", BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000), Instant.now());
        order.addItem(new OrderItem("Test Item", "SKU-" + shortUnique(), 1, BigDecimal.valueOf(1000), true, false));
        return orderRepository.save(order);
    }

    private String unique(String prefix) {
        return prefix + "." + UUID.randomUUID() + "@example.pk";
    }

    private String unique4() {
        return String.valueOf(System.nanoTime() % 10_000);
    }

    /** Short (8 hex char) unique suffix - business reference columns are VARCHAR(30), a full UUID (36 chars) overflows them. */
    private static String shortUnique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void ownerCanReadTheirOwnOrder() {
        var summary = queryService.getOrderSummary(mariaIdentity, mariaOrder.getOrderNumber());
        assertThat(summary.orderNumber()).isEqualTo(mariaOrder.getOrderNumber());
    }

    @Test
    void cannotReadAnotherCustomersOrderSummaryByOrderNumber() {
        assertThatThrownBy(() -> queryService.getOrderSummary(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void cannotReadAnotherCustomersShipmentStatus() {
        assertThatThrownBy(() -> queryService.getShipmentStatus(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void cannotReadAnotherCustomersPaymentStatus() {
        assertThatThrownBy(() -> queryService.getPaymentStatus(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void cannotReadAnotherCustomersRefund() {
        assertThatThrownBy(() -> queryService.getLatestRefund(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void cannotReadAnotherCustomersReturnStatus() {
        assertThatThrownBy(() -> queryService.getReturnStatus(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void nonexistentOrderNumberThrowsTheSameExceptionTypeAsSomeoneElsesRealOrder() {
        String fakeOrderNumber = "ORD-NONE-" + shortUnique();

        assertThatThrownBy(() -> queryService.getOrderSummary(mariaIdentity, fakeOrderNumber))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
        assertThatThrownBy(() -> queryService.getOrderSummary(mariaIdentity, ahmedOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
        // Deliberately not asserting anything that would distinguish the two cases -
        // that indistinguishability is the point of this test.
    }

    @Test
    void cannotReadAnotherCustomersTicketByTicketNumber() {
        SupportTicket ahmedTicket = supportTicketRepository.findByCustomerId(ahmed.getId()).get(0);
        assertThatThrownBy(() -> queryService.getTicketStatus(mariaIdentity, ahmedTicket.getTicketNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }

    @Test
    void anonymousIdentityCannotCallCustomerScopedReads() {
        CustomerIdentity anonymous = CustomerIdentity.anonymous();
        assertThatThrownBy(() -> queryService.getOrderSummary(anonymous, mariaOrder.getOrderNumber()))
                .isInstanceOf(InsufficientAssuranceException.class);
    }

    @Test
    void recentOrdersOnlyReturnsTheCallersOwnOrders() {
        var recent = queryService.getRecentOrders(mariaIdentity, 10);
        assertThat(recent).extracting(r -> r.orderNumber()).containsExactly(mariaOrder.getOrderNumber());
    }
}