package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.policy.CancellationNotEligibleException;
import com.voxticket.policy.ReturnNotEligibleException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency regression tests for financial mutations.
 *
 * <p>{@code CancellationService.cancel} and {@code ReturnService.requestReturn}
 * fetch the order row with a {@code PESSIMISTIC_WRITE} lock, so concurrent
 * requests for the same order serialize on the row instead of both passing
 * the eligibility check and creating duplicate refunds/returns. These tests
 * fire two simultaneous requests from separate threads (separate
 * transactions, like two sessions or two application instances) and assert
 * only one financial record is created.
 *
 * <p>Requires Docker (Testcontainers). The per-session lock in
 * {@code ConversationRuntime} only serializes turns within one session on one
 * instance - it cannot protect against this scenario.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class ConcurrentMutationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private ReturnRequestRepository returnRequestRepository;
    @Autowired
    private CancellationService cancellationService;
    @Autowired
    private ReturnService returnService;

    @Test
    void concurrentCancellationsOfSameOrderProduceExactlyOneRefund() throws Exception {
        Customer customer = customerRepository.save(new Customer("Test", "User",
                unique() + "@example.pk", "+9230012" + shortNum()));
        Order order = orderRepository.save(new Order("ORD-" + shortId(), customer, "PKR",
                BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.valueOf(2000), Instant.now()));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        VerifiedOrderRef ref = new VerifiedOrderRef(order.getId(), order.getOrderNumber(),
                customer.getId(), IdentityAssurance.PHONE_MATCHED, Instant.now());

        List<String> outcomes = runConcurrently(2,
                () -> {
                    try {
                        cancellationService.cancel(ref);
                        return "CANCELLED";
                    } catch (CancellationNotEligibleException e) {
                        return "DENIED";
                    }
                });

        assertThat(outcomes).containsExactlyInAnyOrder("CANCELLED", "DENIED");
        assertThat(refundRepository.findByOrderId(order.getId())).hasSize(1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void concurrentReturnRequestsForSingleQuantityItemProduceExactlyOneReturn() throws Exception {
        Customer customer = customerRepository.save(new Customer("Test", "User",
                unique() + "@example.pk", "+9230012" + shortNum()));
        Order order = new Order("ORD-" + shortId(), customer, "PKR",
                BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000), Instant.now());
        String sku = "SKU-" + shortId();
        order.addItem(new OrderItem("Test Product", sku, 1, BigDecimal.valueOf(1000), true, false));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Shipment shipment = new Shipment(order, "TCS", "TCS-" + shortId(), ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(Instant.now().minus(5, ChronoUnit.DAYS));
        shipmentRepository.save(shipment);
        VerifiedOrderRef ref = new VerifiedOrderRef(order.getId(), order.getOrderNumber(),
                customer.getId(), IdentityAssurance.PHONE_MATCHED, Instant.now());

        List<String> outcomes = runConcurrently(2,
                () -> {
                    try {
                        returnService.requestReturn(ref, sku, 1, ReturnReason.WRONG_SIZE);
                        return "RETURNED";
                    } catch (ReturnNotEligibleException e) {
                        return "DENIED";
                    }
                });

        assertThat(outcomes).containsExactlyInAnyOrder("RETURNED", "DENIED");
        assertThat(returnRequestRepository.findByOrderId(order.getId())).hasSize(1);
    }

    /**
     * Runs {@code task} on {@code threads} threads started simultaneously via
     * a latch, each in its own transaction (the services are
     * {@code @Transactional}; this test class deliberately is not).
     */
    private List<String> runConcurrently(int threads, java.util.concurrent.Callable<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for the start signal");
                    }
                    return task.call();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private String unique() {
        return "user." + UUID.randomUUID().toString().substring(0, 8);
    }

    private String shortNum() {
        return String.valueOf(System.nanoTime() % 10_000);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
