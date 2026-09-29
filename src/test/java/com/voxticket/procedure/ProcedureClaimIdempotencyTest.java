package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
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
 * Pass 2D-B cleanup (Tasks 7-9): claim idempotency never depends on exact
 * free-text wording. Same claim identity with rephrased detail is the same
 * request (the original confirmation-bound payload is untouched); a changed
 * item or parsed {@code ClaimReason} is a different request; a material
 * detail correction requires abandon/restart.
 *
 * <p>Mock-based so it runs without Docker.
 */
class ProcedureClaimIdempotencyTest {

    private OwnedOrderItemResolver ownedOrderItemResolver;
    private ClaimService claimService;
    private VerificationService verificationService;
    private ProcedureCoordinator coordinator;

    private UUID customerId;
    private Customer customer;
    private final Map<String, VerifiedOrderRef> refs = new HashMap<>();
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Map<String, OrderItem> itemsByName = new HashMap<>();

    @BeforeEach
    void setUp() {
        OwnedOrderResolver ownedOrderResolver = mock(OwnedOrderResolver.class);
        ownedOrderItemResolver = mock(OwnedOrderItemResolver.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CancellationPolicyService cancellationPolicyService = mock(CancellationPolicyService.class);
        ReturnPolicyService returnPolicyService = mock(ReturnPolicyService.class);
        verificationService = mock(VerificationService.class);
        ReturnService returnService = mock(ReturnService.class);
        claimService = mock(ClaimService.class);
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

        addOrder("ORD-10001", "Blue Widget", "SKU-10001");
        addOrder("ORD-10001", "Green Gizmo", "SKU-10002");
        addOrder("ORD-10002", "Red Gadget", "SKU-10003");

        when(ownedOrderResolver.resolve(any(), any()))
                .thenAnswer(inv -> refs.get(inv.getArgument(1)));
        when(ownedOrderItemResolver.resolve(any(), any()))
                .thenAnswer(inv -> itemsByName.get(inv.getArgument(1)));
        when(ownedOrderItemResolver.resolveBySku(any(), any()))
                .thenAnswer(inv -> {
                    String sku = inv.getArgument(1);
                    return itemsByName.values().stream()
                            .filter(i -> i.getSku().equals(sku))
                            .findFirst()
                            .orElse(null);
                });
        when(orderRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.getArgument(0))));
        Order anyOrder = orders.values().iterator().next();
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.of(new Payment(anyOrder, PaymentMethod.CARD, BigDecimal.TEN, "PKR", PaymentStatus.PAID)));
        when(cancellationPolicyService.evaluate(any(), any()))
                .thenReturn(CancellationEligibility.eligible(PaymentConsequence.NO_REFUND_REQUIRED));
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
        when(returnRequest.getStatus()).thenReturn(ReturnStatus.REQUESTED);
        when(returnService.requestReturn(any(), any(), any(Integer.class), any()))
                .thenReturn(returnRequest);

        var claim = mock(com.voxticket.persistence.entity.OrderClaim.class);
        when(claim.getClaimNumber()).thenReturn("CLM-00001");
        when(claim.getStatus()).thenReturn(ClaimStatus.OPEN);
        when(claimService.fileClaim(any(), any(), any(), any(), any())).thenReturn(claim);
    }

    private void addOrder(String orderNumber, String productName, String sku) {
        VerifiedOrderRef existing = refs.get(orderNumber);
        UUID orderId = existing != null ? existing.orderId() : UUID.randomUUID();
        Order order = orders.computeIfAbsent(orderId,
                id -> new Order(orderNumber, customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now()));
        OrderItem item = new OrderItem(productName, sku, 2, BigDecimal.TEN, true, false);
        order.addItem(item);
        itemsByName.put(productName, item);
        refs.putIfAbsent(orderNumber, new VerifiedOrderRef(orderId, orderNumber, customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
    }

    private ConversationSession session() {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
        return session;
    }

    // ---- Task 7: paraphrase is the same request ----

    @Test
    void paraphrasedProblemDescriptionIsIdempotent() {
        ConversationSession session = session();
        ProcedureOutcome first = coordinator.startClaim(session, "ORD-10001", "Blue Widget",
                "the widget arrived damaged");
        assertThat(first.code()).isEqualTo("CONFIRMATION_REQUIRED");

        // Same order, item, and parsed reason (DAMAGED) - only the free text differs.
        ProcedureOutcome second = coordinator.startClaim(session, "ORD-10001", "Blue Widget",
                "the box was crushed and the widget inside is damaged");

        assertThat(second.code()).isEqualTo("ALREADY_PENDING");
        // Zero state mutation: still one live procedure, nothing executed.
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getDeferredIntent()).isEmpty();
        verify(claimService, times(0)).fileClaim(any(), any(), any(), any(), any());
        verify(verificationService, times(0)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void paraphrasedRepeatKeepsOriginalConfirmationBoundPayload() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        coordinator.startClaim(session, "ORD-10001", "Blue Widget",
                "the box was crushed and the widget inside is damaged");

        // The confirmation-bound description is the original wording the
        // customer is about to confirm - the paraphrase must not overwrite it.
        assertThat(session.getActiveProcedure().orElseThrow().getCollectedData().get("description"))
                .isEqualTo("the widget arrived damaged");
    }

    @Test
    void paraphrasedRepeatInDeferredSlotIsIdempotentToo() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        ProcedureOutcome queued = coordinator.startClaim(session, "ORD-10002", "Red Gadget",
                "the gadget is damaged");
        assertThat(queued.code()).isEqualTo("PROCEDURE_DEFERRED");

        ProcedureOutcome repeat = coordinator.startClaim(session, "ORD-10002", "Red Gadget",
                "the gadget box arrived crushed and damaged");

        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().detail())
                .isEqualTo("the gadget is damaged");
    }

    // ---- Task 8: changed item or parsed reason is a different request ----

    @Test
    void changedItemIsADistinctRequest() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        // Active claim A; a claim for the OTHER item on the same order is
        // genuinely different, so it defers rather than deduplicating.
        ProcedureOutcome other = coordinator.startClaim(session, "ORD-10001", "Green Gizmo",
                "the gizmo arrived damaged");

        assertThat(other.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().itemSku()).isEqualTo("SKU-10002");
    }

    @Test
    void changedParsedReasonIsADistinctRequest() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        ProcedureOutcome queued = coordinator.startClaim(session, "ORD-10002", "Red Gadget",
                "the gadget is damaged");
        assertThat(queued.code()).isEqualTo("PROCEDURE_DEFERRED");

        // DAMAGED -> DEFECTIVE: the parsed reason changed, so this is not the
        // queued request - the occupied slot refuses it instead of deduping.
        ProcedureOutcome differentReason = coordinator.startClaim(session, "ORD-10002", "Red Gadget",
                "the gadget has a manufacturing defect");

        assertThat(differentReason.code()).isNotEqualTo("ALREADY_DEFERRED");
        assertThat(differentReason.code()).isEqualTo("PENDING_REQUEST_LIMIT_REACHED");
        assertThat(session.getDeferredIntent().orElseThrow().reasonName()).isEqualTo("DAMAGED");
    }

    @Test
    void changedItemInDeferredSlotIsADistinctRequest() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        ProcedureOutcome queued = coordinator.startClaim(session, "ORD-10002", "Red Gadget",
                "the gadget is damaged");
        assertThat(queued.code()).isEqualTo("PROCEDURE_DEFERRED");

        ProcedureOutcome differentItem = coordinator.startClaim(session, "ORD-10001", "Green Gizmo",
                "the gizmo arrived damaged");

        assertThat(differentItem.code()).isNotEqualTo("ALREADY_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().itemSku()).isEqualTo("SKU-10003");
    }

    // ---- explicit detail correction requires abandon/restart ----

    @Test
    void materialDetailCorrectionRequiresAbandonAndRestart() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");

        ProcedureOutcome abandoned = coordinator.abandonActiveProcedure(session);
        assertThat(abandoned.code()).isEqualTo("ACTIVE_PROCEDURE_ABANDONED");
        verify(claimService, times(0)).fileClaim(any(), any(), any(), any(), any());

        ProcedureOutcome corrected = coordinator.startClaim(session, "ORD-10001", "Blue Widget",
                "correction: the widget arrived damaged AND the charger is missing");
        assertThat(corrected.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getActiveProcedure().orElseThrow().getCollectedData().get("description"))
                .isEqualTo("correction: the widget arrived damaged AND the charger is missing");
    }

    // ---- Task 9: the confirmed claim executes exactly once ----

    @Test
    void confirmedClaimExecutesExactlyOnce() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");

        ProcedureOutcome filed = coordinator.confirmActive(session);
        assertThat(filed.code()).isEqualTo("CLAIM_FILED");
        assertThat(filed.metadata()).containsEntry("claimNumber", "CLM-00001");
        verify(claimService, times(1)).fileClaim(any(), any(), any(), any(), any());

        // A stray second confirmation cannot re-execute: the claim already
        // cleared the active slot.
        ProcedureOutcome secondConfirm = coordinator.confirmActive(session);
        assertThat(secondConfirm.code()).isEqualTo("NO_PENDING_CONFIRMATION");
        verify(claimService, times(1)).fileClaim(any(), any(), any(), any(), any());
    }

    @Test
    void claimConfirmationMetadataNeverExposesInternalIdentifiers() {
        ConversationSession session = session();
        coordinator.startClaim(session, "ORD-10001", "Blue Widget", "the widget arrived damaged");
        ProcedureOutcome filed = coordinator.confirmActive(session);

        assertThat(filed.metadata().keySet())
                .doesNotContain("customerId", "orderId", "procedureId", "itemSku", "sku");
        assertThat(filed.metadata()).containsEntry("orderReference", "ORD-10001");
    }
}
