package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.audit.AuditEventBus;
import com.voxticket.service.ClaimService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression coverage for transaction/audit consistency (Phase 1 issue: success
 * recorded before the DB transaction commits).
 *
 * <p>P1: the audit service publishes each event to the async
 * {@link AuditEventBus} instead of committing its own
 * independent transaction. {@link ProcedureCoordinator#confirmActive} and
 * {@link ProcedureCoordinator#requestHumanSupport} must still not run inside
 * an outer transaction: with one, {@code EXECUTION_SUCCEEDED} /
 * {@code PROCEDURE_COMPLETED} / {@code ESCALATED} would be published before
 * the domain transaction committed, leaving a false success trail if that
 * transaction later rolled back. The coordinator still relies on the domain
 * services' own transactions (committed on return) and only publishes
 * audit events afterwards - and this test flushes the bus before asserting,
 * so it observes exactly what the async writer would persist.
 *
 * <p>Requires Docker (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
class ProcedureCoordinatorCommitFailureTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ProcedureCoordinator procedureCoordinator;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ConversationSessionRecordRepository sessionRecordRepository;
    @Autowired
    private ConversationEventRecordRepository eventRecordRepository;
    @Autowired
    private AuditEventBus auditEventBus;

    /** Simulates the domain transaction failing to commit. */
    @MockitoBean
    private ClaimService claimService;

    /** Simulates the escalation ticket insert failing to commit. */
    @MockitoBean
    private SupportTicketRepository supportTicketRepository;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User",
                "commitfail." + UUID.randomUUID().toString().substring(0, 8) + "@example.pk",
                "+9230014" + String.valueOf(System.nanoTime() % 10_000)));
    }

    @Test
    void confirmActiveRecordsNoFalseSuccessWhenDomainCommitFails() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        String sku = order.getItems().get(0).getSku();
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome started = procedureCoordinator.startClaim(session, order.getOrderNumber(), sku, "arrived damaged");
        assertThat(started.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getActiveProcedure()).isPresent();

        // The domain transaction rolls back (e.g. commit failure) - the
        // service never returns a persisted claim.
        when(claimService.fileClaim(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("simulated commit failure"));

        assertThatThrownBy(() -> procedureCoordinator.confirmActive(session))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated commit failure");

        List<ConversationEventType> eventTypes = eventTypesFor(session);
        // No false success may remain: the failure is recorded truthfully,
        // but never as a success.
        assertThat(eventTypes).doesNotContain(
                ConversationEventType.EXECUTION_SUCCEEDED,
                ConversationEventType.PROCEDURE_COMPLETED);
        assertThat(eventTypes).contains(ConversationEventType.EXECUTION_FAILED);
        // The session must not carry the completed-procedure footprint.
        assertThat(session.getRecentActions())
                .noneMatch(a -> a.type() == RecentActionType.CLAIM_FILED);
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void requestHumanSupportRecordsNoFalseEscalationWhenTicketCommitFails() {
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        // The ticket insert fails to commit - the escalation must not be
        // recorded anywhere: not in the session, not in durable audit.
        when(supportTicketRepository.save(any()))
                .thenThrow(new RuntimeException("simulated commit failure"));

        assertThatThrownBy(() -> procedureCoordinator.requestHumanSupport(session, "need a human"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated commit failure");

        assertThat(session.isEscalated()).isFalse();
        assertThat(eventTypesFor(session)).doesNotContain(ConversationEventType.ESCALATED);
    }

    @Test
    void confirmActiveAndRequestHumanSupportDoNotRunInAnOuterTransaction() throws Exception {
        // Structural guard for the commit-then-record ordering: the audit
        // service commits independently (REQUIRES_NEW), so an outer
        // @Transactional here would record success before the domain commit.
        Method confirmActive = ProcedureCoordinator.class.getMethod("confirmActive", ConversationSession.class);
        Method requestHumanSupport = ProcedureCoordinator.class.getMethod(
                "requestHumanSupport", ConversationSession.class, String.class);
        assertThat(confirmActive.getAnnotation(Transactional.class))
                .as("confirmActive must not run in an outer transaction").isNull();
        assertThat(requestHumanSupport.getAnnotation(Transactional.class))
                .as("requestHumanSupport must not run in an outer transaction").isNull();
    }

    private List<ConversationEventType> eventTypesFor(ConversationSession session) {
        // P1: audit writes are asynchronous - flush the bus so this observes
        // exactly what the writer would persist.
        auditEventBus.flush();
        return sessionRecordRepository.findBySessionId(session.getSessionId())
                .map(record -> eventRecordRepository.findBySessionIdOrderByCreatedAtAsc(record.getId()))
                .orElseGet(List::of)
                .stream()
                .map(e -> e.getEventType())
                .toList();
    }

    private ConversationSession sessionAt(IdentityAssurance assurance) {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customer.getId(), assurance, customer.getPhone()));
        return session;
    }

    private Order newOrder(BigDecimal amount) {
        Order order = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                customer, "PKR", amount, BigDecimal.ZERO, amount, Instant.now());
        order.addItem(new OrderItem("Test Product", "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                1, amount, true, false));
        return orderRepository.save(order);
    }
}
