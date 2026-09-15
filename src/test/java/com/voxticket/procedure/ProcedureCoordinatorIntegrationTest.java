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
    void fullFlowFromPhoneMatchedThroughOtpDirectlyToExecutedCancellationWithNoSeparateConfirmationStep() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        procedureCoordinator.startCancellation(session, order.getOrderNumber());
        String code = procedureCoordinator.resendVerificationCode(session).metadata().get("devOtp");

        ProcedureOutcome executed = procedureCoordinator.submitVerificationCode(session, code);

        assertThat(executed.code()).isEqualTo("CANCELLED");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        // The action-bound OTP never upgraded session-wide identity assurance.
        assertThat(session.getCustomerIdentity().assuranceLevel()).isEqualTo(IdentityAssurance.PHONE_MATCHED);
    }

    @Test
    void evenAnAlreadyOtpVerifiedSessionStillRequiresAFreshActionBoundOtpForCancellation() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        // Simulates a session that was already OTP_VERIFIED from some earlier, unrelated action.
        ConversationSession session = sessionAt(IdentityAssurance.OTP_VERIFIED);

        ProcedureOutcome outcome = procedureCoordinator.startCancellation(session, order.getOrderNumber());

        assertThat(outcome.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(outcome.metadata()).isEmpty();
    }

    @Test
    void anOtpVerifiedForOneCancellationDoesNotAuthorizeCancellingADifferentOrder() {
        Order orderA = newOrder(BigDecimal.valueOf(500));
        Order orderB = newOrder(BigDecimal.valueOf(700));
        paymentRepository.save(new Payment(orderA, PaymentMethod.COD, orderA.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        paymentRepository.save(new Payment(orderB, PaymentMethod.COD, orderB.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        procedureCoordinator.startCancellation(session, orderA.getOrderNumber());
        String codeA = procedureCoordinator.resendVerificationCode(session).metadata().get("devOtp");
        ProcedureOutcome executedA = procedureCoordinator.submitVerificationCode(session, codeA);
        assertThat(executedA.code()).isEqualTo("CANCELLED");

        ProcedureOutcome challengeB = procedureCoordinator.startCancellation(session, orderB.getOrderNumber());
        assertThat(challengeB.code()).isEqualTo("VERIFICATION_REQUIRED");

        ProcedureOutcome reusedCodeAttempt = procedureCoordinator.submitVerificationCode(session, codeA);
        assertThat(reusedCodeAttempt.success()).isFalse();
        assertThat(orderRepository.findById(orderB.getId()).orElseThrow().getOrderStatus()).isNotEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aFailureDuringOtpExecutionPropagatesRatherThanBeingSwallowed() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);
        procedureCoordinator.startCancellation(session, order.getOrderNumber());
        String code = procedureCoordinator.resendVerificationCode(session).metadata().get("devOtp");

        // Simulate the order becoming ineligible between the request and OTP verification.
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.saveAndFlush(order);

        assertThatThrownBy(() -> procedureCoordinator.submitVerificationCode(session, code))
                .isInstanceOf(CancellationNotEligibleException.class);
        assertThat(session.getActiveProcedure()).isEmpty();
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

    @Test
    void singleItemOrderAutoResolvesForAClaimWithoutNeedingAnItemReference() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome outcome = procedureCoordinator.startClaim(session, order.getOrderNumber(), "", "arrived damaged");

        assertThat(outcome.code()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void ambiguousItemDescriptionAsksForClarificationInsteadOfGuessing() {
        Order order = newOrder(BigDecimal.valueOf(2000));
        order.addItem(new OrderItem("Blue Cotton Shirt", "SKU-EXTRA-" + java.util.UUID.randomUUID(), 1, BigDecimal.valueOf(1000), true, false));
        orderRepository.saveAndFlush(order);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        ConversationSession session = sessionAt(IdentityAssurance.PHONE_MATCHED);

        ProcedureOutcome outcome = procedureCoordinator.startClaim(session, order.getOrderNumber(), "", "one item was damaged");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("ITEM_REQUIRED");
    }

}