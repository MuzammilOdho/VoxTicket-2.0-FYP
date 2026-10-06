package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationLanguage;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.DirectProcedureResponseRenderer;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.ResourceNotFoundForAccountException;
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
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderItemRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.policy.PolicyDecision;
import com.voxticket.policy.ReturnDenialReason;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pass 2D-B cleanup (Tasks 3-6, 10-13): promotion failure isolation.
 *
 * <p>An unexpected exception while promoting a deferred intent must never
 * mask, roll back semantically, or misreport an already-completed active
 * mutation; the queued intent must survive technical failures; no
 * partially-created live procedure may survive; and promotion re-resolves
 * items by retained scoped identity, never by display text.
 *
 * <p>Mock-based so it runs without Docker.
 */
class ProcedurePromotionSafetyTest {

    private OwnedOrderResolver ownedOrderResolver;
    private OwnedOrderItemResolver ownedOrderItemResolver;
    private OrderRepository orderRepository;
    private ReturnPolicyService returnPolicyService;
    private VerificationService verificationService;
    private ConversationAuditService auditService;
    private ProcedureCoordinator coordinator;

    private UUID customerId;
    private Customer customer;
    private final Map<String, VerifiedOrderRef> refs = new HashMap<>();
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Map<String, OrderItem> itemsBySku = new HashMap<>();
    private final Map<UUID, OrderItem> defaultItemByOrder = new HashMap<>();

    private final DirectProcedureResponseRenderer renderer = new DirectProcedureResponseRenderer();

    @BeforeEach
    void setUp() {
        ownedOrderResolver = mock(OwnedOrderResolver.class);
        ownedOrderItemResolver = mock(OwnedOrderItemResolver.class);
        orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        CancellationPolicyService cancellationPolicyService = mock(CancellationPolicyService.class);
        returnPolicyService = mock(ReturnPolicyService.class);
        verificationService = mock(VerificationService.class);
        ReturnService returnService = mock(ReturnService.class);
        ClaimService claimService = mock(ClaimService.class);
        ActionPolicyService actionPolicyService = mock(ActionPolicyService.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        ReferenceNumberGenerator referenceNumberGenerator = mock(ReferenceNumberGenerator.class);
        auditService = mock(ConversationAuditService.class);

        coordinator = new ProcedureCoordinator(
                ownedOrderResolver, ownedOrderItemResolver, actionPolicyService,
                orderRepository, paymentRepository, customerRepository, supportTicketRepository,
                cancellationPolicyService, returnPolicyService, mock(CancellationService.class),
                returnService, claimService, referenceNumberGenerator,
                verificationService, mock(TurnMetrics.class), auditService);

        customerId = UUID.randomUUID();
        customer = new Customer("Test", "User", "user." + UUID.randomUUID() + "@example.pk", "+923001234567");
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        addOrder("ORD-10001", "Blue Widget", "SKU-10001");
        addOrder("ORD-10002", "Red Gadget", "SKU-10002");

        when(ownedOrderResolver.resolve(any(), any()))
                .thenAnswer(inv -> refs.get(inv.getArgument(1)));
        when(ownedOrderItemResolver.resolve(any(), any()))
                .thenAnswer(inv -> defaultItemByOrder.get(((VerifiedOrderRef) inv.getArgument(0)).orderId()));
        when(ownedOrderItemResolver.resolveBySku(any(), any()))
                .thenAnswer(inv -> itemsBySku.get(inv.getArgument(1)));
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
        UUID orderId = UUID.randomUUID();
        Order order = new Order(orderNumber, customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        OrderItem item = new OrderItem(productName, sku, 2, BigDecimal.TEN, true, false);
        order.addItem(item);
        orders.put(orderId, order);
        itemsBySku.put(sku, item);
        defaultItemByOrder.put(orderId, item);
        refs.put(orderNumber, new VerifiedOrderRef(orderId, orderNumber, customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
    }

    private ConversationSession session() {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
        return session;
    }

    /** Active claim A on ORD-10001 plus a deferred cancellation B on ORD-10002; A is then confirmed. */
    private ConversationSession claimAWithDeferredCancellationB() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureOutcome deferred = coordinator.startCancellation(session, "ORD-10002");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        ProcedureOutcome filed = coordinator.confirmActive(session);
        assertThat(filed.code()).isEqualTo("CLAIM_FILED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        return session;
    }

    /** Active claim A on ORD-10001 plus a deferred return B on ORD-10002; A is then confirmed. */
    private ConversationSession claimAWithDeferredReturnB() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "Red Gadget", "damaged", "1");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        ProcedureOutcome filed = coordinator.confirmActive(session);
        assertThat(filed.code()).isEqualTo("CLAIM_FILED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        return session;
    }

    // ---- Task 6A: unexpected failure during ownership resolution ----

    @Test
    void promotionThrowDuringOwnershipResolutionKeepsDeferredIntentAndReportsSafeFailure() {
        ConversationSession session = claimAWithDeferredCancellationB();
        DeferredProcedureIntent queued = session.getDeferredIntent().orElseThrow();

        when(ownedOrderResolver.resolve(any(), any()))
                .thenThrow(new RuntimeException("database unavailable"));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        // The completed claim is not misreported: no EXECUTION_FAILED, a
        // contained promotion failure instead.
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        // The queued customer request survives the technical failure.
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queued);
        // No partially-created live procedure survives.
        assertThat(session.getActiveProcedure()).isEmpty();
        // Bounded observability: the failure is audited without exception text.
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordEvent(any(), anyInt(), eq(ConversationEventType.DEFERRED_PROMOTION_FAILED), detail.capture());
        assertThat(detail.getValue()).doesNotContain("database unavailable");
        assertThat(detail.getValue()).contains("ORD-10002");
    }

    // ---- Task 6B: unexpected failure during eligibility evaluation ----

    @Test
    void promotionThrowDuringEligibilityEvaluationKeepsDeferredIntent() {
        ConversationSession session = claimAWithDeferredReturnB();

        when(returnPolicyService.evaluate(any(), any()))
                .thenThrow(new RuntimeException("policy backend down"));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    // ---- Task 6C: unexpected failure before OTP/confirmation creation ----

    @Test
    void promotionThrowBeforeOtpCreationKeepsDeferredIntent() {
        ConversationSession session = claimAWithDeferredReturnB();

        when(orderRepository.findById(any()))
                .thenThrow(new RuntimeException("database unavailable"));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    // ---- Final cleanup: a technical promotion failure never loses the queued request ----

    @Test
    void promotionThrowDuringOtpIssuanceRestoresDeferredIntent() {
        ConversationSession session = claimAWithDeferredReturnB();
        DeferredProcedureIntent queued = session.getDeferredIntent().orElseThrow();

        when(verificationService.issueChallenge(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("otp backend down"));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        // The partial live procedure must not survive ...
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
        verify(verificationService).invalidatePendingChallenge(any());
        // ... and the queued customer request is restored: a technical
        // failure must never lose it.
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queued);
    }

    @Test
    void promotionRetryAfterFailureIssuesFreshOtpAuthority() {
        ConversationSession session = claimAWithDeferredReturnB();

        when(verificationService.issueChallenge(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("otp backend down"));
        Optional<ProcedureOutcome> failed = coordinator.promoteDeferredIntent(session);
        assertThat(failed.orElseThrow().code()).isEqualTo("PROMOTION_FAILED");
        assertThat(session.getDeferredIntent()).isPresent();

        // The OTP backend recovers: the retry promotes the restored intent
        // with a brand-new challenge. The failed attempt left no challenge
        // behind that could authorize anything.
        doReturn(VerificationOutcome.challengeIssued("A verification code was sent.", Map.of()))
                .when(verificationService).issueChallenge(any(), any(), any(), any());
        Optional<ProcedureOutcome> retried = coordinator.promoteDeferredIntent(session);

        assertThat(retried).isPresent();
        assertThat(retried.get().code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isPresent();
        // Fresh OTP authority: the retry issued a challenge for a brand-new
        // procedure id. The failed attempt's partial procedure was dropped
        // and its challenge invalidated, so no old OTP can authorize this.
        ArgumentCaptor<UUID> procedureIds = ArgumentCaptor.forClass(UUID.class);
        verify(verificationService, times(2)).issueChallenge(any(), any(), procedureIds.capture(), anyString());
        assertThat(procedureIds.getAllValues()).hasSize(2);
        assertThat(procedureIds.getAllValues().get(0)).isNotEqualTo(procedureIds.getAllValues().get(1));
        assertThat(session.getActiveProcedure().orElseThrow().getProcedureId())
                .isEqualTo(procedureIds.getAllValues().get(1));
        verify(verificationService).invalidatePendingChallenge(any());
    }

    // ---- Task 6D: normal NOT_ELIGIBLE is a decision, not an exception ----

    @Test
    void promotionCleanupFailureCannotLeavePartialProcedure() {
        ConversationSession session = claimAWithDeferredReturnB();
        DeferredProcedureIntent queued = session.getDeferredIntent().orElseThrow();
        when(verificationService.issueChallenge(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("otp backend down"));
        // The challenge invalidation itself fails (database down): the
        // partial live state must still be dropped so it can never survive,
        // and the queued request must still be restored.
        doThrow(new RuntimeException("db down"))
                .when(verificationService).invalidatePendingChallenge(any());

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queued);
    }

    @Test
    void normalNotEligibleConsumesDeferredIntentAndRendersDeterministicDenial() {
        ConversationSession session = claimAWithDeferredReturnB();

        when(returnPolicyService.evaluate(any(), any()))
                .thenReturn(ReturnEligibility.denied(ReturnDenialReason.RETURN_WINDOW_EXPIRED));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("NOT_ELIGIBLE");
        // An authoritative denial consumes the intent - it is not kept queued.
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();

        String rendered = renderer.render(ConversationLanguage.ENGLISH, promoted.get());
        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-10002");
        assertThat(rendered).contains("Red Gadget");
    }

    // ---- Task 11: the vanished item is refused, not substituted ----

    @Test
    void promotionRefusesWhenDeferredItemNoLongerOnOrder() {
        ConversationSession session = claimAWithDeferredReturnB();

        when(ownedOrderItemResolver.resolveBySku(any(), any()))
                .thenThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "SKU-10002"));

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("ITEM_REQUIRED");
        // Authoritative refusal: the intent is consumed, no live procedure.
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();

        String rendered = renderer.render(ConversationLanguage.ENGLISH, promoted.get());
        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("ORD-10002");
    }

    // ---- Task 12: duplicate display names resolve by scoped identity ----

    @Test
    void promotionSelectsIntendedItemByScopedIdentityDespiteDuplicateDisplayNames() {
        // One order, two identically-named items with distinct internal identities.
        UUID orderId = UUID.randomUUID();
        Order order = new Order("ORD-20001", customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        OrderItem itemA = new OrderItem("Wireless Mouse", "SKU-MOUSE-A", 1, BigDecimal.TEN, true, false);
        OrderItem itemB = new OrderItem("Wireless Mouse", "SKU-MOUSE-B", 1, BigDecimal.TEN, true, false);
        order.addItem(itemA);
        order.addItem(itemB);
        orders.put(orderId, order);
        itemsBySku.put("SKU-MOUSE-A", itemA);
        itemsBySku.put("SKU-MOUSE-B", itemB);
        refs.put("ORD-20001", new VerifiedOrderRef(orderId, "ORD-20001", customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
        // The model disambiguated to item B earlier; free-text resolution is
        // stubbed to that outcome so the deferred intent retains SKU-MOUSE-B.
        defaultItemByOrder.put(orderId, itemB);

        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-20001", "the second wireless mouse", "damaged", "1");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().itemSku()).isEqualTo("SKU-MOUSE-B");
        ProcedureOutcome filed = coordinator.confirmActive(session);
        assertThat(filed.code()).isEqualTo("CLAIM_FILED");

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("VERIFICATION_REQUIRED");
        // The promoted procedure authorizes exactly the originally intended
        // item identity - never an ambiguous display-name match.
        ProcedureState live = session.getActiveProcedure().orElseThrow();
        assertThat(live.getCollectedData().get("itemReference")).isEqualTo("SKU-MOUSE-B");
        assertThat(session.getDeferredIntent()).isEmpty();

        // The model-facing representation still uses safe display info, not the SKU.
        ProcedureToolResult toolResult = ProcedureToolResultMapper.toToolResult("return", promoted.get());
        assertThat(toolResult.details().get("itemName")).isEqualTo("Wireless Mouse");
        assertThat(toolResult.details().values().stream().map(Object::toString))
                .noneMatch(v -> v.contains("SKU-MOUSE-B"));
        assertThat(toolResult.orderReference()).isEqualTo("ORD-20001");
    }

    // ---- promotion still issues fresh OTP for the promoted action ----

    @Test
    void promotedCancellationIssuesFreshOtpChallenge() {
        ConversationSession session = claimAWithDeferredCancellationB();

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getDeferredIntent()).isEmpty();
        verify(verificationService).issueChallenge(any(), any(), any(), eq("ORD-10002"));
    }

    // ---- promotion still requires fresh explicit confirmation for claims ----

    @Test
    void promotedClaimRequiresFreshExplicitConfirmation() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureOutcome deferred = coordinator.startClaim(session, "ORD-10002", "Red Gadget", "the gadget is broken");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        ProcedureOutcome filed = coordinator.confirmActive(session);
        assertThat(filed.code()).isEqualTo("CLAIM_FILED");

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(session.getActiveProcedure()).isPresent();
        // Fresh confirmation payload describes the promoted claim, not the completed one.
        assertThat(session.getActiveProcedure().orElseThrow().getCollectedData().get("description"))
                .isEqualTo("the gadget is broken");

        String rendered = renderer.render(ConversationLanguage.ENGLISH, promoted.get());
        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).contains("Red Gadget");
        assertThat(rendered.toLowerCase()).doesNotContain("refund");
    }

    // ---- the safe promotion notice composes after a successful mutation result ----

    @Test
    void promotionFailedNoticeRendersWithoutGenericFailureText() {
        ProcedureOutcome failed = ProcedureOutcome.error("PROMOTION_FAILED", "internal only");
        String rendered = renderer.render(ConversationLanguage.ENGLISH, failed);

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered).isNotBlank();
    }

    @Test
    void promotionFailedAuditDetailNeverCarriesExceptionText() {
        ConversationSession session = claimAWithDeferredCancellationB();
        when(ownedOrderResolver.resolve(any(), any()))
                .thenThrow(new IllegalStateException("sensitive backend detail"));

        coordinator.promoteDeferredIntent(session);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordEvent(any(), anyInt(), eq(ConversationEventType.DEFERRED_PROMOTION_FAILED), detail.capture());
        assertThat(detail.getValue()).doesNotContain("sensitive backend detail");
    }

    // ---- Final cleanup: incomplete secondary requests can be deferred ----

    /** Active cancellation on ORD-10001; the deferred claim for ORD-10002 names no item and no problem. */
    private ConversationSession cancellationAWithIncompleteClaimB() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startCancellation(session, "ORD-10001");
        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");
        ProcedureState activeBefore = session.getActiveProcedure().orElseThrow();

        doThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "mystery item"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        ProcedureOutcome deferred = coordinator.startClaim(session, "ORD-10002", "mystery item", null);

        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        // No second live ProcedureState: the active cancellation is untouched.
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().orElseThrow()).isSameAs(activeBefore);
        return session;
    }

    @Test
    void activeCancellationWithIncompleteClaimDefersClaim() {
        ConversationSession session = cancellationAWithIncompleteClaimB();

        // Whatever was known is captured; the unknown slots stay null.
        DeferredProcedureIntent intent = session.getDeferredIntent().orElseThrow();
        assertThat(intent.type()).isEqualTo(ProcedureType.CLAIM);
        assertThat(intent.orderNumber()).isEqualTo("ORD-10002");
        assertThat(intent.orderId()).isNotNull();
        assertThat(intent.itemSku()).isNull();
        assertThat(intent.itemDisplayName()).isEqualTo("mystery item");
        assertThat(intent.reasonName()).isNull();
        assertThat(intent.detail()).isNull();
        // The intent is non-authoritative: no procedure, no challenge, no
        // confirmation of its own - the active cancellation is untouched.
        assertThat(session.getActiveProcedure().orElseThrow().getType()).isEqualTo(ProcedureType.CANCELLATION);
    }

    @Test
    void activeClaimWithIncompleteReturnDefersReturn() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureState activeBefore = session.getActiveProcedure().orElseThrow();

        doThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "mystery item"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        // Item unknown and reason/quantity missing: deferred, not clarified now.
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "mystery item", null, null);

        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        // No second live ProcedureState: the active claim is untouched.
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().orElseThrow()).isSameAs(activeBefore);
        DeferredProcedureIntent intent = session.getDeferredIntent().orElseThrow();
        assertThat(intent.type()).isEqualTo(ProcedureType.RETURN);
        assertThat(intent.orderNumber()).isEqualTo("ORD-10002");
        assertThat(intent.itemSku()).isNull();
        assertThat(intent.reasonName()).isNull();
        assertThat(intent.quantity()).isNull();
    }

    @Test
    void differentPartialIntentsAreNotMergedAsDuplicates() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");

        doThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "unknown"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        ProcedureOutcome first = coordinator.startReturn(session, "ORD-10002", "red gadget", null, null);
        assertThat(first.code()).isEqualTo("PROCEDURE_DEFERRED");
        // A different raw item on the same order is a different queued request.
        ProcedureOutcome second = coordinator.startReturn(session, "ORD-10002", "blue widget", null, null);
        assertThat(second.code()).isEqualTo("PENDING_REQUEST_LIMIT_REACHED");
        // The identical raw request repeated is already deferred.
        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10002", "red gadget", null, null);
        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        // The first queued request was never overwritten.
        assertThat(session.getDeferredIntent().orElseThrow().itemDisplayName()).isEqualTo("red gadget");
    }

    @Test
    void deferredIncompleteClaimPromotesToProblemRequired() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");

        doThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "Red Gadget"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        ProcedureOutcome deferred = coordinator.startClaim(session, "ORD-10002", "Red Gadget", null);
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("CLAIM_FILED");

        // At promotion the item resolves again; the missing problem is
        // clarified with the same outcome the model-driven path returns.
        doAnswer(inv -> itemsBySku.get("SKU-10002"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROBLEM_REQUIRED");
        assertThat(promoted.get().metadata()).containsEntry("itemName", "Red Gadget");
        // Authoritative clarification consumes the intent; nothing went live.
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void deferredIncompleteReturnPromotesToItemRequired() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");

        doThrow(new ResourceNotFoundForAccountException("ORDER_ITEM", "mystery item"))
                .when(ownedOrderItemResolver).resolve(any(), any());
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "mystery item", null, null);
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("CLAIM_FILED");

        // The item still cannot be matched at promotion: ITEM_REQUIRED, not a guess.
        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("ITEM_REQUIRED");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void deferredIncompleteReturnPromotesToReasonRequired() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");

        // Item resolves but the reason is missing: deferred with the resolved
        // item identity kept.
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "Red Gadget", null, null);
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        DeferredProcedureIntent intent = session.getDeferredIntent().orElseThrow();
        assertThat(intent.itemSku()).isEqualTo("SKU-10002");
        assertThat(intent.reasonName()).isNull();
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("CLAIM_FILED");

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("REASON_REQUIRED");
        assertThat(promoted.get().metadata()).containsEntry("itemName", "Red Gadget");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void deferredIncompleteReturnPromotesToQuantityRequired() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");

        // "0" is an explicitly invalid quantity: deferred with the raw value
        // kept so promotion re-asks instead of silently defaulting.
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "Red Gadget", "damaged", "0");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        DeferredProcedureIntent intent = session.getDeferredIntent().orElseThrow();
        assertThat(intent.itemSku()).isEqualTo("SKU-10002");
        assertThat(intent.reasonName()).isEqualTo("DAMAGED");
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("CLAIM_FILED");

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("QUANTITY_REQUIRED");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    @Test
    void staleConfirmationCannotAuthorizeAfterFailedPromotion() {
        ConversationSession session = session();
        ProcedureOutcome claimStart = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(claimStart.code()).isEqualTo("CONFIRMATION_REQUIRED");
        ProcedureOutcome deferred = coordinator.startCancellation(session, "ORD-10002");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("CLAIM_FILED");
        DeferredProcedureIntent queued = session.getDeferredIntent().orElseThrow();

        // Promotion creates the cancellation's live state, then the audit
        // write for the OTP issuance fails.
        doThrow(new RuntimeException("audit down"))
                .when(auditService).recordEvent(any(), anyInt(), eq(ConversationEventType.OTP_ISSUED), anyString());

        Optional<ProcedureOutcome> promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("PROMOTION_FAILED");
        // No partial live procedure survives, and the queued request is restored.
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getPendingVerificationChallengeId()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queued);
        // The abandoned confirmation authority is dead: nothing to confirm.
        assertThat(coordinator.confirmActive(session).code()).isEqualTo("NO_PENDING_CONFIRMATION");
    }

    // ---- Pass 2D-B dedupe fix: asymmetric compatibility ----

    /** Active claim on ORD-10001 / Blue Widget, awaiting confirmation. */
    private ConversationSession activeClaimOnOrder1() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(started.code()).isEqualTo("CONFIRMATION_REQUIRED");
        return session;
    }

    @Test
    void incompleteRepeatOfActiveClaimIsAlreadyPending() {
        ConversationSession session = activeClaimOnOrder1();
        ProcedureState activeBefore = session.getActiveProcedure().orElseThrow();

        // Same order, same item, but no problem text supplied this time.
        ProcedureOutcome repeat = coordinator.startClaim(session, "ORD-10001", "Blue Widget", null);

        assertThat(repeat.code()).isEqualTo("ALREADY_PENDING");
        // The active procedure is authoritative and untouched: same instance,
        // still awaiting the original confirmation, nothing queued behind it.
        assertThat(session.getActiveProcedure().orElseThrow()).isSameAs(activeBefore);
        assertThat(activeBefore.getStatus()).isEqualTo(ProcedureStatus.AWAITING_CONFIRMATION);
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(repeat.metadata().get("pendingStage")).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void incompleteRepeatOfActiveReturnIsAlreadyPending() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startReturn(session, "ORD-10001", "Blue Widget", "damaged", "1");
        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");
        ProcedureState activeBefore = session.getActiveProcedure().orElseThrow();

        // Same order, same item, but neither reason nor quantity supplied.
        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10001", "Blue Widget", null, null);

        assertThat(repeat.code()).isEqualTo("ALREADY_PENDING");
        assertThat(session.getActiveProcedure().orElseThrow()).isSameAs(activeBefore);
        assertThat(activeBefore.getStatus()).isEqualTo(ProcedureStatus.AWAITING_VERIFICATION);
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(repeat.metadata().get("pendingStage")).isEqualTo("VERIFICATION_REQUIRED");
    }

    @Test
    void incompleteRepeatOfDeferredClaimIsAlreadyDeferred() {
        ConversationSession session = activeClaimOnOrder1();
        ProcedureOutcome deferred = coordinator.startClaim(session, "ORD-10002", "Red Gadget", "it arrived damaged");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        DeferredProcedureIntent queuedBefore = session.getDeferredIntent().orElseThrow();

        // Same order, same item as the queued claim, but no problem text.
        ProcedureOutcome repeat = coordinator.startClaim(session, "ORD-10002", "Red Gadget", null);

        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        // The queued request is authoritative and untouched - the missing
        // reason was not copied in and nothing was overwritten.
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queuedBefore);
        assertThat(session.getDeferredIntent().orElseThrow().reasonName()).isEqualTo("DAMAGED");
    }

    @Test
    void incompleteRepeatOfDeferredReturnIsAlreadyDeferred() {
        ConversationSession session = activeClaimOnOrder1();
        ProcedureOutcome deferred = coordinator.startReturn(session, "ORD-10002", "Red Gadget", "damaged", "1");
        assertThat(deferred.code()).isEqualTo("PROCEDURE_DEFERRED");
        DeferredProcedureIntent queuedBefore = session.getDeferredIntent().orElseThrow();

        // Same order, same item as the queued return, but no reason/quantity.
        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10002", "Red Gadget", null, null);

        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow()).isEqualTo(queuedBefore);
        assertThat(session.getDeferredIntent().orElseThrow().quantity()).isEqualTo("1");
    }

    @Test
    void differentResolvedItemRemainsDistinct() {
        ConversationSession session = session();
        // Second item on ORD-10001 so "Red Gadget" resolves to a different SKU.
        UUID orderId = refs.get("ORD-10001").orderId();
        OrderItem redGadget = new OrderItem("Red Gadget", "SKU-10001-B", 2, BigDecimal.TEN, true, false);
        orders.get(orderId).addItem(redGadget);
        itemsBySku.put("SKU-10001-B", redGadget);
        // doAnswer: stubbing with when(...) would invoke the existing setUp
        // answer with null matcher arguments.
        doAnswer(inv -> {
            String description = inv.getArgument(1);
            if (description != null && description.toLowerCase(java.util.Locale.ROOT).contains("red")) {
                return redGadget;
            }
            return defaultItemByOrder.get(((VerifiedOrderRef) inv.getArgument(0)).orderId());
        }).when(ownedOrderItemResolver).resolve(any(), any());

        ProcedureOutcome started = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "I haven't received it");
        assertThat(started.code()).isEqualTo("CONFIRMATION_REQUIRED");

        // Same order, different resolved item: a genuinely different request.
        ProcedureOutcome other = coordinator.startClaim(session, "ORD-10001", "Red Gadget", null);

        assertThat(other.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().itemSku()).isEqualTo("SKU-10001-B");
    }

    @Test
    void explicitDifferentReasonRemainsDistinct() {
        ConversationSession session = activeClaimOnOrder1();

        // Same order, same item, but an explicitly different parsed reason.
        ProcedureOutcome other = coordinator.startClaim(session, "ORD-10001", "Blue Widget", "it arrived damaged");

        assertThat(other.code()).isEqualTo("PROCEDURE_DEFERRED");
        // Queued as its own request - never merged into the pending confirmation.
        assertThat(session.getDeferredIntent().orElseThrow().reasonName()).isEqualTo("DAMAGED");
    }

    @Test
    void explicitDifferentQuantityRemainsDistinct() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startReturn(session, "ORD-10001", "Blue Widget", "damaged", "1");
        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");

        // Same order, same item, same reason, but an explicitly different quantity.
        ProcedureOutcome other = coordinator.startReturn(session, "ORD-10001", "Blue Widget", "damaged", "2");

        assertThat(other.code()).isEqualTo("PROCEDURE_DEFERRED");
        assertThat(session.getDeferredIntent().orElseThrow().quantity()).isEqualTo("2");
    }

    @Test
    void compatibleRepeatIssuesNoNewOtpAndMutatesNothing() {
        ConversationSession session = session();
        ProcedureOutcome started = coordinator.startReturn(session, "ORD-10001", "Blue Widget", "damaged", "1");
        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");
        ProcedureState activeBefore = session.getActiveProcedure().orElseThrow();
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());

        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10001", "Blue Widget", null, null);
        assertThat(repeat.code()).isEqualTo("ALREADY_PENDING");

        // No new OTP challenge, no new procedure state, no queued copy, and
        // the live procedure still awaits the original verification.
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        assertThat(session.getActiveProcedure().orElseThrow()).isSameAs(activeBefore);
        assertThat(activeBefore.getStatus()).isEqualTo(ProcedureStatus.AWAITING_VERIFICATION);
        assertThat(session.getDeferredIntent()).isEmpty();

        // And on the confirmation path the original payload still files -
        // the repeat neither confirmed nor altered it.
        ConversationSession claimSession = activeClaimOnOrder1();
        assertThat(coordinator.startClaim(claimSession, "ORD-10001", "Blue Widget", null).code())
                .isEqualTo("ALREADY_PENDING");
        assertThat(coordinator.confirmActive(claimSession).code()).isEqualTo("CLAIM_FILED");
    }
}
