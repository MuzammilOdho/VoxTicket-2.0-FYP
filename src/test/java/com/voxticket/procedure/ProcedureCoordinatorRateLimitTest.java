package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.CancellationService;
import com.voxticket.service.ClaimService;
import com.voxticket.service.ReferenceNumberGenerator;
import com.voxticket.service.ReturnService;
import com.voxticket.verification.VerificationOutcome;
import com.voxticket.verification.VerificationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit-level (all dependencies mocked) rather than a full Testcontainers
 * integration test - this isolates the exact fix (does beginProcedure mark
 * the procedure FAILED before evicting it on a rate-limited OTP issue)
 * without depending on real rate-limit thresholds or database state.
 */
class ProcedureCoordinatorRateLimitTest {

    @Test
    void rateLimitedVerificationClearsTheSlotWithNothingLeftActive() {
        OwnedOrderResolver ownedOrderResolver = mock(OwnedOrderResolver.class);
        VerificationService verificationService = mock(VerificationService.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CancellationPolicyService cancellationPolicyService = mock(CancellationPolicyService.class);

        VerifiedOrderRef ref = new VerifiedOrderRef(UUID.randomUUID(), "ORD-TEST", UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, Instant.now());
        when(ownedOrderResolver.resolve(any(), any())).thenReturn(ref);
        Order order = new Order("ORD-TEST", null, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        when(orderRepository.findById(ref.orderId())).thenReturn(Optional.of(order));
        Payment payment = new Payment(order, PaymentMethod.COD, BigDecimal.TEN, "PKR", PaymentStatus.PENDING);
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())).thenReturn(Optional.of(payment));
        when(cancellationPolicyService.evaluate(order, payment)).thenReturn(new CancellationEligibility(true, null, PaymentConsequence.NO_REFUND_REQUIRED));
        when(verificationService.issueChallenge(any(), any(), any(), any())).thenReturn(VerificationOutcome.error("Too many attempts."));

        ProcedureCoordinator coordinator = new ProcedureCoordinator(
                ownedOrderResolver, mock(OwnedOrderItemResolver.class), mock(ActionPolicyService.class),
                orderRepository, paymentRepository, mock(CustomerRepository.class), mock(SupportTicketRepository.class),
                cancellationPolicyService, mock(ReturnPolicyService.class), mock(CancellationService.class),
                mock(ReturnService.class), mock(ClaimService.class), mock(ReferenceNumberGenerator.class),
                verificationService, mock(TurnMetrics.class), mock(ConversationAuditService.class));

        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.applyResolvedIdentity(new com.voxticket.identity.CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        ProcedureOutcome outcome = coordinator.startCancellation(session, "ORD-TEST");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("VERIFICATION_RATE_LIMITED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getPausedProcedure()).isEmpty();
    }
}