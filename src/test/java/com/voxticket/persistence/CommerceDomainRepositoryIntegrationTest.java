package com.voxticket.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderItemRepository;
import com.voxticket.persistence.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FIX: class-level @Transactional. Repository methods like save()/delete()
 * manage and commit their own transaction internally, so a direct
 * entityManager.flush()/clear() call made afterward has no transaction to
 * run inside. Wrapping each test method in one transaction lets the
 * repository calls join it instead, and Spring's test support rolls that
 * transaction back automatically at the end of each test method - so this
 * also gives us clean, isolated test data with no manual cleanup.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CommerceDomainRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private EntityManager entityManager;

    /** Short (8 hex char) unique suffix - business reference columns are VARCHAR(30), a full UUID (36 chars) overflows them. */
    private static String shortUnique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void savingAnOrderPersistsItsItemsThroughTheAggregateRoot() {
        Customer customer = customerRepository.save(
                new Customer("Test", "User", "test.user." + UUID.randomUUID() + "@example.pk", "+92300" + System.nanoTime() % 10_000_000));

        Order order = new Order("ORD-TEST-" + shortUnique(), customer, "PKR",
                BigDecimal.valueOf(1000), BigDecimal.valueOf(0), BigDecimal.valueOf(1000), Instant.now());
        order.addItem(new OrderItem("Test Product", "SKU-TEST-1", 2, BigDecimal.valueOf(500), true, false));
        Order saved = orderRepository.save(order);

        entityManager.flush();
        entityManager.clear();

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getOrder().getId()).isEqualTo(reloaded.getId());
        assertThat(reloaded.getOrderStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void orderNumberMustBeUniqueAcrossCustomers() {
        Customer a = customerRepository.save(new Customer("A", "One", "a.one." + UUID.randomUUID() + "@example.pk", "+92300" + System.nanoTime() % 10_000_000));
        Customer b = customerRepository.save(new Customer("B", "Two", "b.two." + UUID.randomUUID() + "@example.pk", "+92301" + System.nanoTime() % 10_000_000));

        String duplicateOrderNumber = "ORD-DUP-" + shortUnique();
        orderRepository.saveAndFlush(new Order(duplicateOrderNumber, a, "PKR",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now()));

        Order conflicting = new Order(duplicateOrderNumber, b, "PKR",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());

        assertThatThrownBy(() -> orderRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnOrderCascadesToItsItems() {
        Customer customer = customerRepository.save(
                new Customer("Cascade", "Test", "cascade." + UUID.randomUUID() + "@example.pk", "+92302" + System.nanoTime() % 10_000_000));
        Order order = new Order("ORD-CASCADE-" + shortUnique(), customer, "PKR",
                BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(100), Instant.now());
        order.addItem(new OrderItem("Item", "SKU-CASCADE-1", 1, BigDecimal.valueOf(100), true, false));
        Order saved = orderRepository.saveAndFlush(order);
        UUID orderId = saved.getId();

        orderRepository.delete(saved);
        entityManager.flush();

        assertThat(orderItemRepository.findByOrderId(orderId)).isEmpty();
    }
}