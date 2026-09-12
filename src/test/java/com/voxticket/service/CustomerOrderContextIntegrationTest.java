package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CustomerOrderContextIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

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

    private Customer customer;
    private CustomerIdentity identity;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User", "ctx." + UUID.randomUUID() + "@example.pk", "+9230017" + (System.nanoTime() % 100_000)));
        identity = new CustomerIdentity(customer.getId(), IdentityAssurance.PHONE_MATCHED, customer.getPhone());
    }

    private Order newOrder(BigDecimal amount) {
        Order order = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), customer, "PKR", amount, BigDecimal.ZERO, amount, Instant.now());
        order.addItem(new OrderItem("Test Product", "SKU-" + UUID.randomUUID().toString().substring(0, 8), 1, amount, true, false));
        return orderRepository.save(order);
    }

    @Test
    void orderContextTranslatesRawStatusesIntoFriendlyLanguage() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.AUTHORIZED));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.orderStatus()).doesNotContain("OPEN");
        assertThat(context.payment().status()).isEqualTo("authorized but not yet charged");
        assertThat(context.payment().status()).doesNotContain("AUTHORIZED");
    }

    @Test
    void codOrderShowsZeroAmountCollected() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.payment().amountCollected()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void paidOrderShowsFullAmountCollected() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.payment().amountCollected()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void cancellationEligibilityIsExpressedAsANaturalSentence() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.cancellationEligibility()).contains("Eligible for cancellation").contains("full refund");
    }

    @Test
    void shippedOrderExplainsWhyCancellationIsNotEligible() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Shipment shipment = new Shipment(order, "TCS", "TCS-1", ShipmentStatus.IN_TRANSIT);
        shipmentRepository.save(shipment);
        order.setFulfillmentStatus(com.voxticket.persistence.entity.enums.FulfillmentStatus.FULFILLED);
        orderRepository.save(order);

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.cancellationEligibility()).contains("Not eligible").contains("already shipped");
    }

    @Test
    void anotherCustomersOrderIsStillNotFoundForAccountViaTheAggregateContext() {
        Customer otherCustomer = customerRepository.save(new Customer("Other", "User", "other." + UUID.randomUUID() + "@example.pk", "+9230018" + (System.nanoTime() % 100_000)));
        Order theirOrder = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), otherCustomer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        orderRepository.save(theirOrder);

        assertThatThrownBy(() -> queryService.getOrderContext(identity, theirOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }
}