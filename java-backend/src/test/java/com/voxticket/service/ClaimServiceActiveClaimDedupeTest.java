package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

/**
 * Phase 1A: the active-claim domain invariant at the write boundary.
 *
 * <p>Read-side {@code ClaimPolicyService} reports an OPEN/IN_REVIEW claim as
 * {@code EXISTING_ACTIVE}; {@code ClaimService.fileClaim} must refuse to
 * create a second active claim for the same order item - enforced at the
 * execution boundary (pessimistic item-row lock + pre-check, backed by the
 * partial unique index in {@code V7__dedupe_active_claims_per_item.sql}),
 * not only through prompts or read DTOs.
 *
 * <p>Deliberately NOT {@code @Transactional} at class level: the concurrency
 * test needs the setup rows committed so racer threads (each with their own
 * transaction via the service proxy) can see them.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
class ClaimServiceActiveClaimDedupeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderClaimRepository orderClaimRepository;
    @Autowired
    private ClaimService claimService;

    private Customer newCustomer() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String digits = (UUID.randomUUID().toString().replaceAll("\\D", "") + "00000000").substring(0, 8);
        return customerRepository.save(new Customer("Claim", "Dedupe",
                "claim.dedupe." + tag + "@example.pk", "+92300" + digits));
    }

    private Order newOrderWithItems(Customer customer, String... skus) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Order order = new Order("ORD-CLM-" + tag, customer, "PKR", BigDecimal.valueOf(10000), BigDecimal.ZERO,
                BigDecimal.valueOf(10000), Instant.now());
        for (String sku : skus) {
            order.addItem(new OrderItem("Widget " + sku, sku, 1, BigDecimal.valueOf(5000), true, false));
        }
        return orderRepository.save(order);
    }

    private VerifiedOrderRef refFor(Order order, Customer customer) {
        return new VerifiedOrderRef(order.getId(), order.getOrderNumber(), customer.getId(),
                IdentityAssurance.PHONE_MATCHED, Instant.now());
    }

    private OrderClaim fileClaim(VerifiedOrderRef ref, String sku) {
        return claimService.fileClaim(ref, sku, ClaimReason.DAMAGED, ClaimResolution.MANUAL_REVIEW, "test claim");
    }

    @Test
    void secondActiveClaimForSameItemIsRejected() {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D1");
        VerifiedOrderRef ref = refFor(order, customer);

        OrderClaim first = fileClaim(ref, "SKU-D1");
        assertThat(first.getStatus()).isEqualTo(ClaimStatus.OPEN);

        assertThatThrownBy(() -> fileClaim(ref, "SKU-D1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active claim");
    }

    @Test
    void inReviewClaimAlsoBlocksRefiling() {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D2");
        VerifiedOrderRef ref = refFor(order, customer);

        OrderClaim first = fileClaim(ref, "SKU-D2");
        claimService.markInReview(first.getId());

        assertThatThrownBy(() -> fileClaim(ref, "SKU-D2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active claim");
    }

    @Test
    void refilingAfterResolutionIsAllowed() {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D3");
        VerifiedOrderRef ref = refFor(order, customer);

        OrderClaim first = fileClaim(ref, "SKU-D3");
        claimService.resolveWithoutRefund(first.getId());

        OrderClaim second = fileClaim(ref, "SKU-D3");
        assertThat(second.getStatus()).isEqualTo(ClaimStatus.OPEN);
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void refilingAfterRejectionIsAllowed() {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D4");
        VerifiedOrderRef ref = refFor(order, customer);

        OrderClaim first = fileClaim(ref, "SKU-D4");
        claimService.reject(first.getId());

        OrderClaim second = fileClaim(ref, "SKU-D4");
        assertThat(second.getStatus()).isEqualTo(ClaimStatus.OPEN);
    }

    @Test
    void differentItemsOnSameOrderMayEachHoldAnActiveClaim() {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D5A", "SKU-D5B");
        VerifiedOrderRef ref = refFor(order, customer);

        OrderClaim a = fileClaim(ref, "SKU-D5A");
        OrderClaim b = fileClaim(ref, "SKU-D5B");

        assertThat(a.getStatus()).isEqualTo(ClaimStatus.OPEN);
        assertThat(b.getStatus()).isEqualTo(ClaimStatus.OPEN);
    }

    @Test
    void concurrentFilingsForSameItemYieldExactlyOneClaim() throws Exception {
        Customer customer = newCustomer();
        Order order = newOrderWithItems(customer, "SKU-D6");
        VerifiedOrderRef ref = refFor(order, customer);

        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        fileClaim(ref, "SKU-D6");
                        successes.incrementAndGet();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Exactly one filing wins; the loser gets the clean domain error,
        // never a second active claim row.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(IllegalStateException.class);
        assertThat(failures.get(0).getMessage()).contains("active claim");
        assertThat(orderClaimRepository.findByOrderId(order.getId()))
                .filteredOn(c -> c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.IN_REVIEW)
                .hasSize(1);
    }
}
