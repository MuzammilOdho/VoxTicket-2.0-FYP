package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
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
@Testcontainers(disabledWithoutDocker = true)
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
    @Autowired
    private ClaimService claimService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderClaimRepository orderClaimRepository;

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

    @Test
    void concurrentApprovedInspectionsProduceExactlyOneRefund() throws Exception {
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

        ReturnRequest request = returnService.requestReturn(ref, sku, 1, ReturnReason.WRONG_SIZE);
        returnService.approve(request.getId());
        returnService.markInTransit(request.getId());
        returnService.markReceived(request.getId());
        UUID requestId = request.getId();

        // completeInspection row-locks the return request (PESSIMISTIC_WRITE):
        // the loser finds the request already COMPLETED and must not create a
        // second refund.
        List<String> outcomes = runConcurrently(2,
                () -> {
                    try {
                        returnService.completeInspection(requestId, true);
                        return "INSPECTED";
                    } catch (IllegalStateException e) {
                        return "DENIED";
                    }
                });

        assertThat(outcomes).containsExactlyInAnyOrder("INSPECTED", "DENIED");
        assertThat(refundRepository.findByOrderId(order.getId())).hasSize(1);
        assertThat(returnRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ReturnStatus.COMPLETED);
    }

    @Test
    void concurrentClaimRefundResolutionsProduceExactlyOneRefund() throws Exception {
        Customer customer = customerRepository.save(new Customer("Test", "User",
                unique() + "@example.pk", "+9230012" + shortNum()));
        Order order = new Order("ORD-" + shortId(), customer, "PKR",
                BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000), Instant.now());
        String sku = "SKU-" + shortId();
        order.addItem(new OrderItem("Test Product", sku, 1, BigDecimal.valueOf(1000), true, false));
        orderRepository.save(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        VerifiedOrderRef ref = new VerifiedOrderRef(order.getId(), order.getOrderNumber(),
                customer.getId(), IdentityAssurance.PHONE_MATCHED, Instant.now());

        OrderClaim claim = claimService.fileClaim(ref, sku, ClaimReason.DAMAGED, ClaimResolution.REFUND, "damaged on arrival");
        UUID claimId = claim.getId();

        // resolveWithRefund row-locks the claim (PESSIMISTIC_WRITE): the loser
        // finds the claim already RESOLVED and must not create a second refund.
        List<String> outcomes = runConcurrently(2,
                () -> {
                    try {
                        claimService.resolveWithRefund(claimId, BigDecimal.valueOf(1000));
                        return "RESOLVED";
                    } catch (IllegalStateException e) {
                        return "DENIED";
                    }
                });

        assertThat(outcomes).containsExactlyInAnyOrder("RESOLVED", "DENIED");
        assertThat(refundRepository.findByOrderId(order.getId())).hasSize(1);
        assertThat(orderClaimRepository.findById(claimId).orElseThrow().getStatus())
                .isEqualTo(ClaimStatus.RESOLVED);
    }

    @Test
    void conflictingRefundTransitionsYieldExactlyOneTerminalState() throws Exception {
        Customer customer = customerRepository.save(new Customer("Test", "User",
                unique() + "@example.pk", "+9230012" + shortNum()));
        Order order = orderRepository.save(new Order("ORD-" + shortId(), customer, "PKR",
                BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.valueOf(2000), Instant.now()));
        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Refund refund = refundService.initiateRefund(order, payment, null, BigDecimal.valueOf(2000), RefundReason.CLAIM);
        UUID refundId = refund.getId();

        // markSucceeded/markFailed row-lock the refund (PESSIMISTIC_WRITE):
        // exactly one terminal transition wins; the loser sees a non-PENDING
        // row. Payment status must stay consistent with the winning transition.
        List<String> outcomes = runConcurrently(
                () -> {
                    try {
                        refundService.markSucceeded(refundId);
                        return "SUCCEEDED";
                    } catch (IllegalStateException e) {
                        return "DENIED";
                    }
                },
                () -> {
                    try {
                        refundService.markFailed(refundId);
                        return "FAILED";
                    } catch (IllegalStateException e) {
                        return "DENIED";
                    }
                });

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).contains("DENIED");
        RefundStatus finalStatus = refundRepository.findById(refundId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(RefundStatus.SUCCEEDED, RefundStatus.FAILED);
        // Exactly one terminal transition won; the loser was denied.
        assertThat(outcomes).containsExactlyInAnyOrder(
                finalStatus == RefundStatus.SUCCEEDED ? "SUCCEEDED" : "FAILED", "DENIED");
        PaymentStatus paymentStatus = paymentRepository.findById(payment.getId()).orElseThrow().getStatus();
        if (finalStatus == RefundStatus.SUCCEEDED) {
            assertThat(paymentStatus).isEqualTo(PaymentStatus.REFUNDED);
        } else {
            assertThat(paymentStatus).isEqualTo(PaymentStatus.PAID);
        }
    }

    /**
     * Runs {@code task} on {@code threads} threads started simultaneously via
     * a latch, each in its own transaction (the services are
     * {@code @Transactional}; this test class deliberately is not).
     */
    private List<String> runConcurrently(int threads, java.util.concurrent.Callable<String> task) throws Exception {
        java.util.concurrent.Callable<String>[] tasks = new java.util.concurrent.Callable[threads];
        java.util.Arrays.fill(tasks, task);
        return runConcurrently(tasks);
    }

    /**
     * Runs each of {@code tasks} on its own thread, started simultaneously via
     * a latch, each in its own transaction. Used when the racing operations
     * differ (e.g. success vs failure of the same refund).
     */
    @SafeVarargs
    private List<String> runConcurrently(java.util.concurrent.Callable<String>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch ready = new CountDownLatch(tasks.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<String> task : tasks) {
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

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    /**
     * Phone suffix unique across the whole test run. Previously
     * {@code System.nanoTime() % 10_000} - only ~10k distinct values, and on
     * coarse-clock machines two calls in the same tick return the identical
     * value, violating {@code uk_customers_phone} when this non-transactional
     * test class commits customers across methods.
     */
    private String shortNum() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}
