package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PolicyDecision;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.CancellationService;
import com.voxticket.service.ClaimService;
import com.voxticket.service.ReferenceNumberGenerator;
import com.voxticket.service.ReturnService;
import com.voxticket.verification.VerificationOutcome;
import com.voxticket.verification.VerificationResult;
import com.voxticket.verification.VerificationService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0.6: an {@code Idempotency-Key} on a mutating procedure call makes a transport-level
 * retry return the stored outcome instead of re-executing the mutation.
 *
 * <p>Mock-based so it runs without Docker. The key itself is never derived from
 * caller input and never asserted on beyond equality.
 */
class ProcedureCoordinatorIdempotencyKeyTest {

    private OwnedOrderResolver ownedOrderResolver;
    private OwnedOrderItemResolver ownedOrderItemResolver;
    private OrderRepository orderRepository;
    private VerificationService verificationService;
    private ProcedureCoordinator coordinator;

    private UUID customerId;
    private Customer customer;
    private final Map<String, VerifiedOrderRef> refs = new HashMap<>();
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Map<UUID, OrderItem> defaultItemByOrder = new HashMap<>();

    @BeforeEach
    void setUp() {
        ownedOrderResolver = mock(OwnedOrderResolver.class);
        ownedOrderItemResolver = mock(OwnedOrderItemResolver.class);
        orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CancellationPolicyService cancellationPolicyService = mock(CancellationPolicyService.class);
        ReturnPolicyService returnPolicyService = mock(ReturnPolicyService.class);
        verificationService = mock(VerificationService.class);
        ReturnService returnService = mock(ReturnService.class);
        ClaimService claimService = mock(ClaimService.class);
        ActionPolicyService actionPolicyService = mock(ActionPolicyService.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        ReferenceNumberGenerator referenceNumberGenerator = mock(ReferenceNumberGenerator.class);

        coordinator = new ProcedureCoordinator(
                ownedOrderResolver, ownedOrderItemResolver, actionPolicyService,
                orderRepository, paymentRepository, customerRepository, supportTicketRepository,
                cancellationPolicyService, returnPolicyService, mock(CancellationService.class),
                returnService, claimService, referenceNumberGenerator,
                verificationService, mock(TurnMetrics.class), mock(ConversationAuditService.class));

        customerId = UUID.randomUUID();
        customer = new Customer("Test", "User", "user." + UUID.randomUUID() + "@example.pk", "+923001234567");
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        UUID orderId = UUID.randomUUID();
        Order order = new Order("ORD-10001", customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        OrderItem item = new OrderItem("Blue Widget", "SKU-10001", 2, BigDecimal.TEN, true, false);
        order.addItem(item);
        orders.put(orderId, order);
        defaultItemByOrder.put(orderId, item);
        refs.put("ORD-10001", new VerifiedOrderRef(orderId, "ORD-10001", customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));

        when(ownedOrderResolver.resolve(any(), any()))
                .thenAnswer(inv -> refs.get(inv.getArgument(1)));
        when(ownedOrderItemResolver.resolve(any(), any()))
                .thenAnswer(inv -> defaultItemByOrder.get(((VerifiedOrderRef) inv.getArgument(0)).orderId()));
        when(orderRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.getArgument(0))));
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.of(new Payment(order, PaymentMethod.CARD, BigDecimal.TEN, "PKR", PaymentStatus.PAID)));
        when(returnPolicyService.evaluate(any(), any()))
                .thenReturn(ReturnEligibility.eligible(5));
        when(verificationService.issueChallenge(any(), any(), any(), any()))
                .thenReturn(VerificationOutcome.challengeIssued("A verification code was sent.", Map.of()));
        when(verificationService.verify(any(), any(), any(), any()))
                .thenReturn(VerificationResult.success());
        when(actionPolicyService.evaluate(any(), any()))
                .thenReturn(PolicyDecision.allow());

        var returnRequest = mock(com.voxticket.persistence.entity.ReturnRequest.class);
        when(returnRequest.getReturnNumber()).thenReturn("RTN-00001");
        when(returnRequest.getStatus())
                .thenReturn(com.voxticket.persistence.entity.enums.ReturnStatus.REQUESTED);
        when(returnService.requestReturn(any(), any(), any(Integer.class), any()))
                .thenReturn(returnRequest);
    }

    private ConversationSession session() {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
        return session;
    }

    private ConversationSession returnAwaitingVerification() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startReturn(session, "ORD-10001", "Blue Widget", "damaged", "1");
        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");
        return session;
    }

    @Test
    void sameResendKeyTwiceExecutesOnlyOnce() {
        ConversationSession session = returnAwaitingVerification();

        ProcedureOutcome first = coordinator.resendVerificationCode(session, "key-1");
        assertThat(first.code()).isEqualTo("VERIFICATION_REQUIRED");
        ProcedureOutcome replay = coordinator.resendVerificationCode(session, "key-1");

        assertThat(replay).isEqualTo(first);
        // 1 challenge from startReturn + 1 from the first resend; the replay must not
        // re-execute (a transport retry must not spam the customer with codes).
        verify(verificationService, times(2)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void differentKeysAreIndependent() {
        ConversationSession session = returnAwaitingVerification();

        coordinator.resendVerificationCode(session, "key-1");
        coordinator.resendVerificationCode(session, "key-2");

        verify(verificationService, times(3)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void nullKeyKeepsTheOldNonIdempotentBehavior() {
        ConversationSession session = returnAwaitingVerification();

        coordinator.resendVerificationCode(session, null);
        coordinator.resendVerificationCode(session, null);

        verify(verificationService, times(3)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void blankKeyKeepsTheOldNonIdempotentBehavior() {
        ConversationSession session = returnAwaitingVerification();

        coordinator.resendVerificationCode(session, "   ");
        coordinator.resendVerificationCode(session, "   ");

        verify(verificationService, times(3)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void sameSubmitKeyTwiceVerifiesOnlyOnce() {
        ConversationSession session = returnAwaitingVerification();

        ProcedureOutcome first = coordinator.submitVerificationCode(session, "000000", "key-submit");
        ProcedureOutcome replay = coordinator.submitVerificationCode(session, "000000", "key-submit");

        assertThat(replay).isEqualTo(first);
        // The OTP attempt must be counted exactly once: a retried request must not
        // consume a second verification attempt on the backend.
        verify(verificationService, times(1)).verify(any(), any(), any(), any());
    }
}
