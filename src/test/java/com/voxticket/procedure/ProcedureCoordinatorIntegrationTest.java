package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.voxticket.policy.CancellationNotEligibleException;
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
@SpringBootTest(properties = "voxticket.otp.resend-cooldown-seconds=0")
@Transactional
class ProcedureCoordinatorIntegrationTest {

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
    private ShipmentRepository shipmentRepository;
    @Autowired
    private OrderClaimRepository orderClaimRepository;
    @Autowired
    private SupportTicketRepository supportTicketRepository;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User", unique() + "@example.pk", "+9230014" + shortNum()));
    }

    private ConversationSession sessionAt(IdentityAssurance assurance) {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customer.getId(), assurance, customer.getPhone()));
        return session;
    }

    private Order newOrder(BigDecimal amount) {
        Order order = new Order("ORD-" + shortId(), customer, "PKR", amount, BigDecimal.ZERO, amount, Instant.now());
        order.addItem(new OrderItem("Test Product", "SKU-" + shortId(), 1, amount, true, false));
        return orderRepository.save(order);
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

    @Test
    void claimCanCompleteEndToEndAtPhoneMatchedAssurance() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        String sku = order.getItems().get(0).getSku();
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome started = procedureCoordinator.startClaim(session, order.getOrderNumber(), sku, "arrived damaged");
        assertThat(started.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getActiveProcedure()).isPresent();

        ProcedureOutcome confirmed = procedureCoordinator.confirmActive(session);
        assertThat(confirmed.success()).isTrue();
        assertThat(confirmed.code()).isEqualTo("CLAIM_FILED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(orderClaimRepository.findByOrderId(order.getId())).hasSize(1);
        assertThat(supportTicketRepository.findByCustomerId(customer.getId())).isNotEmpty();
    }


    @Test
    void cancellationCompletesEndToEndAtOtpVerifiedAssurance() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.OTP_VERIFIED);

        ProcedureOutcome started = procedureCoordinator.startCancellation(session, order.getOrderNumber());
        assertThat(started.code()).isEqualTo("CONFIRMATION_REQUIRED");

        ProcedureOutcome confirmed = procedureCoordinator.confirmActive(session);
        assertThat(confirmed.success()).isTrue();
        assertThat(confirmed.code()).isEqualTo("CANCELLED");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void ineligibleCancellationIsRejectedBeforeAnyConfirmationIsRequested() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        shipmentRepository.save(new Shipment(order, "TCS", "TCS-1", ShipmentStatus.IN_TRANSIT));
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.save(order);
        ConversationSession session = sessionAt(IdentityAssurance.OTP_VERIFIED);

        ProcedureOutcome outcome = procedureCoordinator.startCancellation(session, order.getOrderNumber());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("NOT_ELIGIBLE");
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void decliningLeavesNothingExecutedAndClearsTheSlot() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        String sku = order.getItems().get(0).getSku();
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);
        procedureCoordinator.startClaim(session, order.getOrderNumber(), sku, "wrong item");

        ProcedureOutcome declined = procedureCoordinator.declineActive(session);

        assertThat(declined.code()).isEqualTo("DECLINED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(orderClaimRepository.findByOrderId(order.getId())).isEmpty();
    }

    @Test
    void aThirdStatefulProcedureIsRejectedRatherThanDiscardingEitherExisting() {
        Order orderA = newOrder(BigDecimal.valueOf(500));
        Order orderB = newOrder(BigDecimal.valueOf(600));
        Order orderC = newOrder(BigDecimal.valueOf(700));
        paymentRepository.save(new Payment(orderA, PaymentMethod.CARD, orderA.getTotalAmount(), "PKR", PaymentStatus.PAID));
        paymentRepository.save(new Payment(orderB, PaymentMethod.CARD, orderB.getTotalAmount(), "PKR", PaymentStatus.PAID));
        paymentRepository.save(new Payment(orderC, PaymentMethod.CARD, orderC.getTotalAmount(), "PKR", PaymentStatus.PAID));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        procedureCoordinator.startClaim(session, orderA.getOrderNumber(), orderA.getItems().get(0).getSku(), "damaged");
        ProcedureOutcome second = procedureCoordinator.startClaim(session, orderB.getOrderNumber(), orderB.getItems().get(0).getSku(), "wrong item");
        assertThat(second.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getPausedProcedure()).isPresent();

        ProcedureOutcome third = procedureCoordinator.startClaim(session, orderC.getOrderNumber(), orderC.getItems().get(0).getSku(), "missing item");

        assertThat(third.success()).isFalse();
        assertThat(third.code()).isEqualTo("TOO_MANY_ACTIVE_PROCEDURES");
        // Neither existing procedure was discarded:
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getPausedProcedure()).isPresent();
    }

    @Test
    void confirmingWithNothingPendingReturnsAClearError() {
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome outcome = procedureCoordinator.confirmActive(session);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("NO_PENDING_CONFIRMATION");
    }

    @Test
    void aFailureDuringExecutionPropagatesRatherThanBeingSwallowed() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.OTP_VERIFIED);
        procedureCoordinator.startCancellation(session, order.getOrderNumber());

        // Simulate the order becoming ineligible between the request and the confirmation (e.g.
        // it shipped in the meantime) - CancellationService re-checks eligibility internally and
        // will throw at confirm time even though the initial request passed.
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.saveAndFlush(order);

        assertThatThrownBy(() -> procedureCoordinator.confirmActive(session))
                .isInstanceOf(CancellationNotEligibleException.class);

        // The session isn't left stuck waiting on a confirmation that can never succeed.
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void successfulClaimIsRecordedAsARecentActionForFollowUpReference() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        String sku = order.getItems().get(0).getSku();
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);
        procedureCoordinator.startClaim(session, order.getOrderNumber(), sku, "arrived damaged");

        procedureCoordinator.confirmActive(session);

        assertThat(session.getRecentActions()).anySatisfy(action -> {
            assertThat(action.type()).isEqualTo(RecentActionType.CLAIM_FILED);
            assertThat(action.target()).isEqualTo(order.getOrderNumber());
        });
    }

    @Test
    void repeatedHumanSupportRequestsReuseTheSameTicketRatherThanCreatingANewOne() {
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome first = procedureCoordinator.requestHumanSupport(session, "need help");
        ProcedureOutcome second = procedureCoordinator.requestHumanSupport(session, "still need help");

        assertThat(first.code()).isEqualTo("ESCALATED");
        assertThat(second.code()).isEqualTo("ALREADY_ESCALATED");
        assertThat(supportTicketRepository.findByCustomerId(customer.getId())).hasSize(1);
    }

    
    @Test
    void fullFlowFromPhoneMatchedThroughOtpToExecutedCancellation() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        procedureCoordinator.startCancellation(session, order.getOrderNumber());
        // The tool-visible outcome no longer carries the code (see the test above) - fetch it
        // through the safe, model-invisible resend path instead, exactly as a real dev/tester would.
        ProcedureOutcome resent = procedureCoordinator.resendVerificationCode(session);
        String code = resent.metadata().get("devOtp");

        ProcedureOutcome verified = procedureCoordinator.submitVerificationCode(session, code);
        assertThat(verified.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getCustomerIdentity().assuranceLevel()).isEqualTo(IdentityAssurance.OTP_VERIFIED);

        ProcedureOutcome executed = procedureCoordinator.confirmActive(session);
        assertThat(executed.code()).isEqualTo("CANCELLED");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void wrongVerificationCodeKeepsTheProcedureWaitingRatherThanFailingOutright() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);
        procedureCoordinator.startCancellation(session, order.getOrderNumber());

        ProcedureOutcome result = procedureCoordinator.submitVerificationCode(session, "000000");

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("VERIFICATION_FAILED");
        assertThat(session.getActiveProcedure()).isPresent(); // still waiting, not discarded
    }

    @Test
    void cancellationAtPhoneMatchedIssuesAVerificationChallengeWithoutLeakingTheCodeToTheModel() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome outcome = procedureCoordinator.startCancellation(session, order.getOrderNumber());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.code()).isEqualTo("VERIFICATION_REQUIRED");
        // This exact outcome is what a @Tool method hands back to the model - it must never
        // carry the OTP, unlike resendVerificationCode's outcome, which is model-invisible.
        assertThat(outcome.metadata()).isEmpty();
        assertThat(session.getActiveProcedure()).isPresent();
    }


}