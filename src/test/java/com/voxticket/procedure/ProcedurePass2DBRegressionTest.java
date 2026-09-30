package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.VerifiedOrderRef;
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
import com.voxticket.policy.ReturnDenialReason;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.CancellationService;
import com.voxticket.service.ClaimService;
import com.voxticket.service.ReturnService;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.observability.TurnMetrics;
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
 * Pass 2D-B regression coverage: one live {@link ProcedureState} per
 * session, idempotent duplicate requests, the single deferred intent, safe
 * abandonment with OTP invalidation, and quantity-aware authorization.
 * Mock-based so it runs without Docker.
 */
class ProcedurePass2DBRegressionTest {

    private OwnedOrderResolver ownedOrderResolver;
    private OwnedOrderItemResolver ownedOrderItemResolver;
    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private CancellationPolicyService cancellationPolicyService;
    private ReturnPolicyService returnPolicyService;
    private VerificationService verificationService;
    private ReturnService returnService;
    private ClaimService claimService;
    private ActionPolicyService actionPolicyService;
    private SupportTicketRepository supportTicketRepository;
    private CustomerRepository customerRepository;
    private ProcedureCoordinator coordinator;

    private UUID customerId;
    private Customer customer;
    private final Map<String, VerifiedOrderRef> refs = new HashMap<>();
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Map<UUID, OrderItem> items = new HashMap<>();

    @BeforeEach
    void setUp() {
        ownedOrderResolver = mock(OwnedOrderResolver.class);
        ownedOrderItemResolver = mock(OwnedOrderItemResolver.class);
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        cancellationPolicyService = mock(CancellationPolicyService.class);
        returnPolicyService = mock(ReturnPolicyService.class);
        verificationService = mock(VerificationService.class);
        returnService = mock(ReturnService.class);
        claimService = mock(ClaimService.class);
        actionPolicyService = mock(ActionPolicyService.class);
        supportTicketRepository = mock(SupportTicketRepository.class);
        customerRepository = mock(CustomerRepository.class);
        var referenceNumberGenerator = mock(com.voxticket.service.ReferenceNumberGenerator.class);

        coordinator = new ProcedureCoordinator(
                ownedOrderResolver, ownedOrderItemResolver, actionPolicyService,
                orderRepository, paymentRepository, customerRepository, supportTicketRepository,
                cancellationPolicyService, returnPolicyService, mock(CancellationService.class),
                returnService, claimService, referenceNumberGenerator,
                verificationService, mock(TurnMetrics.class), mock(ConversationAuditService.class));

        customerId = UUID.randomUUID();
        customer = new Customer("Test", "User", "user." + UUID.randomUUID() + "@example.pk", "+923001234567");
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(referenceNumberGenerator.ticketNumber()).thenReturn("TKT-00001");

        addOrder("ORD-10001");
        addOrder("ORD-10002");
        addOrder("ORD-10003");

        when(ownedOrderResolver.resolve(any(), any()))
                .thenAnswer(inv -> refs.get(inv.getArgument(1)));
        when(ownedOrderItemResolver.resolve(any(), any()))
                .thenAnswer(inv -> items.get(((VerifiedOrderRef) inv.getArgument(0)).orderId()));
        // Pass 2D-B cleanup: promotion re-resolves the retained item identity
        // by SKU scoped to the freshly re-verified order, never by display
        // text, so the fixture mirrors that lookup as well.
        when(ownedOrderItemResolver.resolveBySku(any(), any()))
                .thenAnswer(inv -> {
                    String sku = inv.getArgument(1);
                    return items.values().stream()
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
                .thenReturn(com.voxticket.policy.PolicyDecision.allow());

        var returnRequest = mock(com.voxticket.persistence.entity.ReturnRequest.class);
        when(returnRequest.getReturnNumber()).thenReturn("RTN-00001");
        when(returnRequest.getStatus()).thenReturn(ReturnStatus.REQUESTED);
        when(returnService.requestReturn(any(), any(), any(Integer.class), any()))
                .thenReturn(returnRequest);

        var claim = mock(com.voxticket.persistence.entity.OrderClaim.class);
        when(claim.getClaimNumber()).thenReturn("CLM-00001");
        when(claim.getStatus()).thenReturn(ClaimStatus.OPEN);
        when(claimService.fileClaim(any(), any(), any(), any(), any())).thenReturn(claim);

        when(supportTicketRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void addOrder(String orderNumber) {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(orderNumber, customer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        OrderItem item = new OrderItem("Test Product " + orderNumber, "SKU-" + orderNumber, 2, BigDecimal.TEN, true, false);
        order.addItem(item);
        orders.put(orderId, order);
        items.put(orderId, item);
        refs.put(orderNumber, new VerifiedOrderRef(orderId, orderNumber, customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
    }

    private ConversationSession session() {
        ConversationSession session = ConversationSession.newSession("s-" + UUID.randomUUID(), Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));
        return session;
    }

    private String itemName(String orderNumber) {
        return items.get(refs.get(orderNumber).orderId()).getProductName();
    }

    // ---- Case A: identical active request is idempotent, zero new challenges ----

    @Test
    void duplicateCancellationReusesThePendingChallenge() {
        ConversationSession session = session();

        ProcedureOutcome first = coordinator.startCancellation(session, "ORD-10001");
        ProcedureOutcome second = coordinator.startCancellation(session, "ORD-10001");

        assertThat(first.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(second.success()).isTrue();
        assertThat(second.code()).isEqualTo("ALREADY_PENDING");
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.CANCELLATION);
        assertThat(session.getDeferredIntent()).isEmpty();
    }

    @Test
    void duplicateReturnReusesThePendingChallenge() {
        ConversationSession session = session();

        ProcedureOutcome first = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "2");
        ProcedureOutcome second = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "2");

        assertThat(first.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(second.code()).isEqualTo("ALREADY_PENDING");
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        assertThat(session.getActiveProcedure()).isPresent();
    }

    // ---- Live-trace defect: duplicate claim must not create a second confirmation or DB record ----

    @Test
    void duplicateClaimCreatesNoSecondConfirmationOrRecord() {
        ConversationSession session = session();

        ProcedureOutcome first = coordinator.startClaim(session, "ORD-10001", itemName("ORD-10001"), "arrived damaged");
        ProcedureOutcome second = coordinator.startClaim(session, "ORD-10001", itemName("ORD-10001"), "arrived damaged");

        assertThat(first.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(second.code()).isEqualTo("ALREADY_PENDING");

        ProcedureOutcome confirmed = coordinator.confirmActive(session);
        assertThat(confirmed.code()).isEqualTo("CLAIM_FILED");
        verify(claimService, times(1)).fileClaim(any(), any(), any(), any(), any());

        // A second confirmation attempt finds nothing pending - no second DB record.
        ProcedureOutcome reconfirm = coordinator.confirmActive(session);
        assertThat(reconfirm.success()).isFalse();
        assertThat(reconfirm.code()).isEqualTo("NO_PENDING_CONFIRMATION");
        verify(claimService, times(1)).fileClaim(any(), any(), any(), any(), any());
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    // ---- Cases B-D: deferral, duplicate deferral, queue-full ----

    @Test
    void differentRequestWhileActiveDefersWithoutAuthorization() {
        ConversationSession session = session();

        ProcedureOutcome first = coordinator.startCancellation(session, "ORD-10001");
        ProcedureOutcome second = coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        assertThat(first.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(second.success()).isTrue();
        assertThat(second.code()).isEqualTo("PROCEDURE_DEFERRED");
        // No OTP or confirmation was created for the deferred request.
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.CANCELLATION);
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().type()).isEqualTo(ProcedureType.RETURN);
        assertThat(session.getDeferredIntent().get().orderNumber()).isEqualTo("ORD-10002");
        assertThat(session.getDeferredIntent().get().quantity()).isEqualTo("2");
    }

    @Test
    void repeatOfDeferredRequestIsAlreadyDeferred() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        DeferredProcedureIntent before = session.getDeferredIntent().orElseThrow();

        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().createdAt()).isEqualTo(before.createdAt());
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void thirdDistinctRequestRefusesWithBothSlotsUntouched() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        DeferredProcedureIntent deferredBefore = session.getDeferredIntent().orElseThrow();

        ProcedureOutcome third = coordinator.startClaim(session, "ORD-10003", itemName("ORD-10003"), "missing item");

        assertThat(third.success()).isFalse();
        assertThat(third.code()).isEqualTo("PENDING_REQUEST_LIMIT_REACHED");
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.CANCELLATION);
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().orderNumber()).isEqualTo(deferredBefore.orderNumber());
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
    }

    // ---- Cases E-F: abandonment invalidates the OTP ----

    @Test
    void abandonActiveInvalidatesChallengeAndClearsState() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        ProcedureOutcome abandoned = coordinator.abandonActiveProcedure(session);

        assertThat(abandoned.success()).isTrue();
        assertThat(abandoned.code()).isEqualTo("ACTIVE_PROCEDURE_ABANDONED");
        verify(verificationService, times(1)).invalidatePendingChallenge(session);
        assertThat(session.getActiveProcedure()).isEmpty();

        // A replacement request starts fresh with its own new challenge.
        ProcedureOutcome replacement = coordinator.startCancellation(session, "ORD-10001");
        assertThat(replacement.code()).isEqualTo("VERIFICATION_REQUIRED");
        verify(verificationService, times(2)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void abandonWithNothingActiveIsHarmless() {
        ConversationSession session = session();

        ProcedureOutcome outcome = coordinator.abandonActiveProcedure(session);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("NO_ACTIVE_PROCEDURE");
        verifyNoInteractions(verificationService);
    }

    @Test
    void abandonmentLeavesDeferredIntentQueued() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        coordinator.abandonActiveProcedure(session);

        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().type()).isEqualTo(ProcedureType.RETURN);
    }

    // ---- Phase 6: deferred-procedure UX completion ----

    @Test
    void abandonmentSurfacesTheSurvivingQueuedRequest() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        ProcedureOutcome abandoned = coordinator.abandonActiveProcedure(session);

        assertThat(abandoned.code()).isEqualTo("ACTIVE_PROCEDURE_ABANDONED");
        assertThat(abandoned.message()).contains("nothing was executed")
                .contains("still queued")
                .contains("return on order ORD-10002");
        // The queued request itself is untouched - only surfaced.
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().type()).isEqualTo(ProcedureType.RETURN);
    }

    @Test
    void abandonmentWithoutAQueuedRequestKeepsThePlainMessage() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");

        ProcedureOutcome abandoned = coordinator.abandonActiveProcedure(session);

        assertThat(abandoned.code()).isEqualTo("ACTIVE_PROCEDURE_ABANDONED");
        assertThat(abandoned.message())
                .isEqualTo("The pending request has been dropped - nothing was executed.");
    }

    @Test
    void contestedSlotConflictNamesTheQueuedRequestWithoutAfterwards() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        coordinator.abandonActiveProcedure(session);

        ProcedureOutcome contested = coordinator.startClaim(session, "ORD-10003", itemName("ORD-10003"), "missing item");

        assertThat(contested.code()).isEqualTo("DEFERRED_REQUEST_PENDING");
        // No active procedure exists here, so "afterwards" would be wrong.
        assertThat(contested.message()).contains("return on order ORD-10002")
                .contains("queued")
                .doesNotContain("afterwards");
    }

    @Test
    void deferredRepeatWhileActiveKeepsBehindTheCurrentOneWording() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        // A compatible repeat of the queued request while the first procedure
        // is still live: still queued behind the current one.
        ProcedureOutcome repeat = coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        assertThat(repeat.code()).isEqualTo("ALREADY_DEFERRED");
        assertThat(repeat.message()).contains("already queued behind the current one");
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getDeferredIntent()).isPresent();
    }

    // ---- Case G: discard deferred ----

    @Test
    void discardDeferredLeavesActiveUntouched() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        ProcedureOutcome discarded = coordinator.discardDeferredIntent(session);

        assertThat(discarded.success()).isTrue();
        assertThat(discarded.code()).isEqualTo("DEFERRED_INTENT_DISCARDED");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.CANCELLATION);
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        verify(verificationService, never()).invalidatePendingChallenge(any());
    }

    // ---- Human support never touches procedure state ----

    @Test
    void humanSupportEscalationLeavesProcedureStateUntouched() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        ProcedureOutcome escalated = coordinator.requestHumanSupport(session, "wants a person");

        assertThat(escalated.code()).isEqualTo("ESCALATED");
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.CANCELLATION);
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().type()).isEqualTo(ProcedureType.RETURN);
        verify(verificationService, never()).invalidatePendingChallenge(any());
        verify(claimService, never()).fileClaim(any(), any(), any(), any(), any());
        verify(returnService, never()).requestReturn(any(), any(), any(Integer.class), any());
    }

    // ---- Case H: promotion re-resolves and re-checks ----

    @Test
    void promotionReissuesFreshOtpAndRechecksEligibility() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        coordinator.declineActive(session);

        var promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("VERIFICATION_REQUIRED");
        // Fresh OTP for the promoted return - the deferred intent carried none.
        verify(verificationService, times(2)).issueChallenge(any(), any(), any(), any());
        // Eligibility was evaluated once at deferral time and again at promotion.
        verify(returnPolicyService, times(2)).evaluate(any(), any());
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.RETURN);
        assertThat(session.getDeferredIntent()).isEmpty();
    }

    @Test
    void promotionHonorsRevokedEligibilityWithoutExecuting() {
        when(returnPolicyService.evaluate(any(), any()))
                .thenReturn(ReturnEligibility.eligible(5),
                        ReturnEligibility.denied(ReturnDenialReason.ITEM_FINAL_SALE));
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        coordinator.declineActive(session);

        var promoted = coordinator.promoteDeferredIntent(session);

        assertThat(promoted).isPresent();
        assertThat(promoted.get().code()).isEqualTo("NOT_ELIGIBLE");
        verify(returnService, never()).requestReturn(any(), any(), any(Integer.class), any());
        // The consumed intent is not resurrected.
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isEmpty();
    }

    // ---- Cases K: quantity is part of the authorization ----

    @Test
    void quantityChangeDoesNotReuseTheActiveAuthorization() {
        ConversationSession session = session();

        ProcedureOutcome first = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "2");
        ProcedureOutcome changed = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "3");

        assertThat(first.code()).isEqualTo("VERIFICATION_REQUIRED");
        // A different quantity is a different action - it defers, it never
        // reuses the pending OTP-backed authorization.
        assertThat(changed.code()).isEqualTo("PROCEDURE_DEFERRED");
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
        assertThat(session.getActiveProcedure().get().getCollectedData()).containsEntry("quantity", "2");
    }

    @Test
    void invalidQuantityAsksAgainWithoutStartingAProcedure() {
        ConversationSession session = session();

        ProcedureOutcome zero = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "0");
        ProcedureOutcome nonsense = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "a few");

        assertThat(zero.code()).isEqualTo("QUANTITY_REQUIRED");
        assertThat(nonsense.code()).isEqualTo("QUANTITY_REQUIRED");
        assertThat(session.getActiveProcedure()).isEmpty();
        verify(verificationService, never()).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void excessiveQuantityIsDeniedAgainstCurrentPolicy() {
        ConversationSession session = session();

        ProcedureOutcome outcome = coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "99");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.code()).isEqualTo("NOT_ELIGIBLE");
        assertThat(outcome.metadata()).containsEntry("maxReturnableQuantity", "5");
        assertThat(session.getActiveProcedure()).isEmpty();
        verify(verificationService, never()).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void executedReturnUsesTheAuthorizedQuantity() {
        ConversationSession session = session();

        coordinator.startReturn(session, "ORD-10001", itemName("ORD-10001"), "damaged", "2");
        ProcedureOutcome executed = coordinator.submitVerificationCode(session, "482916");

        assertThat(executed.code()).isEqualTo("RETURN_STARTED");
        verify(returnService, times(1)).requestReturn(any(), any(), eq(2), any());
    }

    // ---- Case L/M: the freed slot is contested ----

    @Test
    void newRequestContestingAQueuedIntentIsRefusedWithoutMutation() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        coordinator.abandonActiveProcedure(session);

        ProcedureOutcome contested = coordinator.startClaim(session, "ORD-10003", itemName("ORD-10003"), "missing item");

        assertThat(contested.success()).isFalse();
        assertThat(contested.code()).isEqualTo("DEFERRED_REQUEST_PENDING");
        // Nothing was mutated: no active procedure, the queued intent is intact,
        // and no new challenge was issued.
        assertThat(session.getActiveProcedure()).isEmpty();
        assertThat(session.getDeferredIntent()).isPresent();
        assertThat(session.getDeferredIntent().get().type()).isEqualTo(ProcedureType.RETURN);
        verify(verificationService, times(1)).issueChallenge(any(), any(), any(), any());
    }

    @Test
    void confirmingTheQueuedRequestAfterAbandonmentStartsItFresh() {
        ConversationSession session = session();

        coordinator.startCancellation(session, "ORD-10001");
        coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");
        coordinator.abandonActiveProcedure(session);

        ProcedureOutcome started = coordinator.startReturn(session, "ORD-10002", itemName("ORD-10002"), "damaged", "2");

        assertThat(started.code()).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(session.getDeferredIntent()).isEmpty();
        assertThat(session.getActiveProcedure()).isPresent();
        assertThat(session.getActiveProcedure().get().getType()).isEqualTo(ProcedureType.RETURN);
        verify(verificationService, times(2)).issueChallenge(any(), any(), any(), any());
    }
}
