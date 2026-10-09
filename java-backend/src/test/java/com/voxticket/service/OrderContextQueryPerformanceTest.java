package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
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
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.ReturnReason;
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
import com.voxticket.service.dto.OrderSupportContext;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 7: pins the order-read statement budget on real Postgres.
 *
 * <p>{@code getOrderSupportContext} is the heaviest model-facing read. Before
 * Phase 7 it issued 8 base statements plus an N+1 chain per associated row:
 * one query per return for its items, one per return item for the order item,
 * one per refund for the return request, two per claim (order item, ticket),
 * a duplicate shipment query inside the return-policy evaluation, and two
 * lazy hops per bulk-loaded return item. The fetch-join read methods and the
 * shipment-passing {@code evaluateAll} overload collapse all of that to a
 * flat, volume-independent budget:
 *
 * <ol>
 *   <li>ownership resolution ({@code findByOrderNumberAndCustomerId})</li>
 *   <li>order + items ({@code findByIdWithItems})</li>
 *   <li>latest payment</li>
 *   <li>shipments</li>
 *   <li>returns + return items + their order items</li>
 *   <li>refunds + return request + payment</li>
 *   <li>claims + order item + support ticket</li>
 *   <li>bulk return-item quantities for the return-capability evaluation</li>
 * </ol>
 *
 * <p>Docker-gated like the other persistence integration tests: the sandbox
 * has no Docker daemon, so these run on the user's Docker-capable machine.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderContextQueryPerformanceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"));

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
    private OrderClaimRepository orderClaimRepository;

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private Customer customer;
    private String orderNumber;

    @BeforeEach
    void seed() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        String tag = UUID.randomUUID().toString().substring(0, 8);
        customer = customerRepository.save(
                new Customer("Perf", "Test", "perf." + tag + "@example.pk", "+92300" + tag.replace("-", "0")));

        Order order = new Order("ORD-QP-" + tag, customer, "PKR",
                BigDecimal.valueOf(3000), BigDecimal.ZERO, BigDecimal.valueOf(3000),
                Instant.now().minus(10, ChronoUnit.DAYS));
        OrderItem itemA = new OrderItem("Item A", "QP-A-" + tag, 2, BigDecimal.valueOf(1000), true, false);
        OrderItem itemB = new OrderItem("Item B", "QP-B-" + tag, 1, BigDecimal.valueOf(1000), true, false);
        order.addItem(itemA);
        order.addItem(itemB);
        order = orderRepository.save(order);
        orderNumber = order.getOrderNumber();

        Payment payment = paymentRepository.save(
                new Payment(order, PaymentMethod.CARD, BigDecimal.valueOf(3000), "PKR", PaymentStatus.PAID));

        Shipment inTransit = new Shipment(order, "TCS", "TRK-QP-1-" + tag, ShipmentStatus.IN_TRANSIT);
        shipmentRepository.save(inTransit);
        Shipment delivered = new Shipment(order, "TCS", "TRK-QP-2-" + tag, ShipmentStatus.DELIVERED);
        delivered.setDeliveredAt(Instant.now().minus(5, ChronoUnit.DAYS));
        shipmentRepository.save(delivered);

        // Two returns, two items each - the old N+1 chain scaled with these.
        ReturnRequest return1 = new ReturnRequest("RTN-QP-1-" + tag, order, ReturnReason.DAMAGED);
        return1.addItem(new ReturnItem(itemA, 1, ReturnReason.DAMAGED));
        return1.addItem(new ReturnItem(itemB, 1, ReturnReason.DAMAGED));
        returnRequestRepository.save(return1);
        ReturnRequest return2 = new ReturnRequest("RTN-QP-2-" + tag, order, ReturnReason.WRONG_SIZE);
        return2.addItem(new ReturnItem(itemA, 1, ReturnReason.WRONG_SIZE));
        return2.addItem(new ReturnItem(itemB, 1, ReturnReason.WRONG_SIZE));
        returnRequestRepository.save(return2);

        // One refund linked to a return, one standalone (cancellation-style).
        refundRepository.save(new Refund("RFD-QP-1-" + tag, order, payment, return1,
                BigDecimal.valueOf(1000), RefundReason.RETURN));
        refundRepository.save(new Refund("RFD-QP-2-" + tag, order, payment, null,
                BigDecimal.valueOf(500), RefundReason.CANCELLATION));

        // One claim with a ticket, one without.
        SupportTicket ticket = supportTicketRepository.save(new SupportTicket(
                "TCK-QP-1-" + tag, customer, order, TicketCategory.CLAIM, TicketPriority.HIGH, "perf claim"));
        OrderClaim claim1 = new OrderClaim("CLM-QP-1-" + tag, order, itemA, ClaimReason.DAMAGED, ClaimResolution.REFUND);
        claim1.setSupportTicket(ticket);
        orderClaimRepository.save(claim1);
        orderClaimRepository.save(
                new OrderClaim("CLM-QP-2-" + tag, order, itemB, ClaimReason.MISSING_ITEM, ClaimResolution.REPLACEMENT));
    }

    private CustomerIdentity identity() {
        return new CustomerIdentity(customer.getId(), IdentityAssurance.PHONE_MATCHED, "+923001234567");
    }

    private long statementsFor(Runnable read) {
        statistics.clear();
        read.run();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void supportContextStaysAtFlatStatementBudget() {
        long statements = statementsFor(() -> {
            OrderSupportContext context = queryService.getOrderSupportContext(identity(), orderNumber);
            // The fetch joins must actually carry the data - same content as
            // the old lazy loads, just without the per-row queries.
            assertThat(context.items()).hasSize(2);
            assertThat(context.shipments()).hasSize(2);
            assertThat(context.returns()).hasSize(2);
            assertThat(context.returns()).allSatisfy(r -> assertThat(r.items()).hasSize(2));
            assertThat(context.refunds()).hasSize(2);
            assertThat(context.claims()).hasSize(2);
        });

        // 8 flat statements (see class javadoc), independent of how many
        // returns, claims, or items the order carries. Before Phase 7 this
        // fixture issued 20+.
        assertThat(statements).isLessThanOrEqualTo(8);
    }

    @Test
    void orderSummaryIssuesTwoStatements() {
        long statements = statementsFor(() -> {
            var summary = queryService.getOrderSummary(identity(), orderNumber);
            assertThat(summary.items()).hasSize(2);
        });

        assertThat(statements).isLessThanOrEqualTo(2);
    }

    @Test
    void recentOrdersIssuesOneStatement() {
        long statements = statementsFor(() -> queryService.getRecentOrders(identity(), 5));

        assertThat(statements).isLessThanOrEqualTo(1);
    }

    @Test
    void latestRefundIssuesTwoStatements() {
        long statements = statementsFor(() -> {
            var latest = queryService.getLatestRefund(identity(), orderNumber);
            assertThat(latest).isPresent();
        });

        assertThat(statements).isLessThanOrEqualTo(2);
    }
}
