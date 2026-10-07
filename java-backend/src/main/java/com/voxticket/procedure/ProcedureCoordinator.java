package com.voxticket.procedure;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.ConversationFocus;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.RecentAction;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.identity.AmbiguousItemException;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.observability.TurnTrace;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.entity.enums.VerificationPurpose;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.ActionPolicyService;
import com.voxticket.policy.ActionRequest;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.policy.PolicyDecision;
import com.voxticket.policy.PolicyOutcome;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.CancellationService;
import com.voxticket.service.ClaimService;
import com.voxticket.service.ReferenceNumberGenerator;
import com.voxticket.service.ReturnService;
import com.voxticket.verification.VerificationOutcome;
import com.voxticket.verification.VerificationResult;
import com.voxticket.verification.VerificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProcedureCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcedureCoordinator.class);
    private static final Duration PENDING_ACTION_TTL = Duration.ofMinutes(5);
    private static final Set<String> STARTED_CODES = Set.of("CONFIRMATION_REQUIRED", "VERIFICATION_REQUIRED", "ALREADY_ESCALATED");

    private final OwnedOrderResolver ownedOrderResolver;
    private final OwnedOrderItemResolver ownedOrderItemResolver;
    private final ActionPolicyService actionPolicyService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final CancellationPolicyService cancellationPolicyService;
    private final ReturnPolicyService returnPolicyService;
    private final CancellationService cancellationService;
    private final ReturnService returnService;
    private final ClaimService claimService;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final VerificationService verificationService;
    private final TurnMetrics turnMetrics;
    private final ConversationAuditService auditService;

    public ProcedureCoordinator(
            OwnedOrderResolver ownedOrderResolver,
            OwnedOrderItemResolver ownedOrderItemResolver,
            ActionPolicyService actionPolicyService,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            CustomerRepository customerRepository,
            SupportTicketRepository supportTicketRepository,
            CancellationPolicyService cancellationPolicyService,
            ReturnPolicyService returnPolicyService,
            CancellationService cancellationService,
            ReturnService returnService,
            ClaimService claimService,
            ReferenceNumberGenerator referenceNumberGenerator,
            VerificationService verificationService,
            TurnMetrics turnMetrics,
            ConversationAuditService auditService) {
        this.ownedOrderResolver = ownedOrderResolver;
        this.ownedOrderItemResolver = ownedOrderItemResolver;
        this.actionPolicyService = actionPolicyService;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.cancellationPolicyService = cancellationPolicyService;
        this.returnPolicyService = returnPolicyService;
        this.cancellationService = cancellationService;
        this.returnService = returnService;
        this.claimService = claimService;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.verificationService = verificationService;
        this.turnMetrics = turnMetrics;
        this.auditService = auditService;
    }

    // ---- Starting procedures (called from ProcedureRequestTools, i.e. by the model) ----

    public ProcedureOutcome startCancellation(ConversationSession session, String orderReference) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return withMetadata(recordOutcome(session, ProcedureType.CANCELLATION,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos),
                    Map.of("orderReference", orderReference));
        }
        session.recordFocusOrder(ref.orderNumber());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())
                .orElseThrow(() -> new IllegalStateException("Order has no payment record: " + ref.orderNumber()));
        CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, payment);
        if (!eligibility.eligible()) {
            return withMetadata(recordOutcome(session, ProcedureType.CANCELLATION,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This order is not eligible for cancellation (" + eligibility.denialReason() + ")."),
                    ref.orderNumber(), startNanos),
                    cancellationMetadata(ref.orderNumber(), eligibility));
        }
        String description = "cancel order " + ref.orderNumber() + "." + describePaymentConsequence(eligibility.paymentConsequence());
        ProcedureOutcome raw = beginProcedure(session, ProcedureType.CANCELLATION, ref, Map.of(), true, description, null, startNanos);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("orderReference", ref.orderNumber());
        if ("VERIFICATION_REQUIRED".equals(raw.code())) {
            extra.put("paymentConsequence", eligibility.paymentConsequence().name());
        }
        return withMetadata(raw, extra);
    }

    public ProcedureOutcome startReturn(ConversationSession session, String orderReference, String itemReference, String reasonText, String quantityText) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos),
                    Map.of("orderReference", orderReference));
        }
        session.recordFocusOrder(ref.orderNumber());
        return startReturnForOrder(session, ref, itemReference, reasonText, quantityText, startNanos);
    }

    /**
     * Pass 2D-B final cleanup: the return validation path once the order is
     * resolved. When another procedure is active, a request that cannot be
     * validated yet is deferred with whatever fields are already known -
     * the deferred slots are never collected immediately, and no second
     * live ProcedureState is started. Shared by the model-driven start and
     * by promotion of a partially captured intent (where the active slot is
     * empty, so clarification outcomes are returned, never deferred).
     */
    private ProcedureOutcome startReturnForOrder(ConversationSession session, VerifiedOrderRef ref,
            String itemReference, String reasonText, String quantityText, long startNanos) {
        OrderItem item;
        try {
            item = resolveItemWithFocusFallback(session, ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.RETURN,
                        deferSecondaryIntent(session, ProcedureType.RETURN, ref, null, itemReference, reasonText, quantityText, null),
                        ref.orderNumber(), startNanos);
            }
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber()));
        } catch (AmbiguousItemException e) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.RETURN,
                        deferSecondaryIntent(session, ProcedureType.RETURN, ref, null, itemReference, reasonText, quantityText, null),
                        ref.orderNumber(), startNanos);
            }
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"),
                    ref.orderNumber(), startNanos),
                    ambiguousItemMetadata(ref.orderNumber(), e));
        }
        session.recordFocusItem(item.getSku(), item.getProductName());
        return startReturnWithItem(session, ref, item, reasonText, quantityText, startNanos);
    }

    /**
     * Pass 2D-B cleanup: the shared tail of a return start once the item is
     * resolved - eligibility, reason, quantity, then the single
     * procedure-entry point. Used both by the model-driven start (free-text
     * item resolution) and by deferred promotion (strict scoped
     * item-identity resolution, never display text).
     */
    private ProcedureOutcome startReturnWithItem(ConversationSession session, VerifiedOrderRef ref, OrderItem item,
            String reasonText, String quantityText, long startNanos) {
        session.recordFocusItem(item.getSku(), item.getProductName());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        ReturnEligibility eligibility = returnPolicyService.evaluate(order, item);
        if (!eligibility.eligible()) {
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This item is not eligible for return (" + eligibility.denialReason() + ")."),
                    ref.orderNumber(), startNanos),
                    returnDenialMetadata(ref.orderNumber(), item.getProductName(), eligibility));
        }
        if (reasonText == null || reasonText.isBlank()) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.RETURN,
                        deferSecondaryIntent(session, ProcedureType.RETURN, ref, item, null, null, quantityText, null),
                        ref.orderNumber(), startNanos);
            }
            return withMetadata(recordOutcome(session, ProcedureType.RETURN, ProcedureOutcome.error("REASON_REQUIRED",
                            "Could you tell me why you'd like to return the " + item.getProductName() + " - for example wrong size, damaged, or you changed your mind?"),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber(), "itemName", item.getProductName()));
        }
        ReturnReason reason = parseReturnReason(reasonText);
        int quantity = parseReturnQuantity(quantityText);
        if (quantity <= 0) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.RETURN,
                        deferSecondaryIntent(session, ProcedureType.RETURN, ref, item, null, reasonText, quantityText, null),
                        ref.orderNumber(), startNanos);
            }
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                            ProcedureOutcome.error("QUANTITY_REQUIRED",
                                    "How many units of the " + item.getProductName() + " would you like to return?"),
                            ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber(), "itemName", item.getProductName(),
                            "maxReturnableQuantity", Integer.toString(eligibility.maxReturnableQuantity())));
        }
        if (quantity > eligibility.maxReturnableQuantity()) {
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                            ProcedureOutcome.error("NOT_ELIGIBLE",
                                    "Only " + eligibility.maxReturnableQuantity() + " unit(s) of the " + item.getProductName()
                                            + " can still be returned."),
                            ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber(), "itemName", item.getProductName(),
                            "maxReturnableQuantity", Integer.toString(eligibility.maxReturnableQuantity())));
        }
        Map<String, String> data = Map.of(
                "itemReference", item.getSku(),
                "reason", reason.name(),
                "quantity", Integer.toString(quantity));
        String description = quantity == 1
                ? "start a return for " + item.getProductName() + " from order " + ref.orderNumber()
                : "start a return for " + quantity + " units of " + item.getProductName() + " from order " + ref.orderNumber();
        ProcedureOutcome raw = beginProcedure(session, ProcedureType.RETURN, ref, data, true, description, item.getProductName(), startNanos);
        return withMetadata(raw, Map.of(
                "orderReference", ref.orderNumber(),
                "itemName", item.getProductName(),
                "returnReason", reason.name(),
                "quantity", Integer.toString(quantity)));
    }

    public ProcedureOutcome startClaim(ConversationSession session, String orderReference, String itemReference, String problemText) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos),
                    Map.of("orderReference", orderReference));
        }
        session.recordFocusOrder(ref.orderNumber());
        return startClaimForOrder(session, ref, itemReference, problemText, startNanos);
    }

    /**
     * Pass 2D-B final cleanup: the claim validation path once the order is
     * resolved. When another procedure is active, a request that cannot be
     * validated yet is deferred with whatever fields are already known -
     * the deferred slots are never collected immediately, and no second
     * live ProcedureState is started. Shared by the model-driven start and
     * by promotion of a partially captured intent (where the active slot is
     * empty, so clarification outcomes are returned, never deferred).
     */
    private ProcedureOutcome startClaimForOrder(ConversationSession session, VerifiedOrderRef ref,
            String itemReference, String problemText, long startNanos) {
        OrderItem item;
        try {
            item = resolveItemWithFocusFallback(session, ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.CLAIM,
                        deferSecondaryIntent(session, ProcedureType.CLAIM, ref, null, itemReference, null, null, problemText),
                        ref.orderNumber(), startNanos);
            }
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber()));
        } catch (AmbiguousItemException e) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.CLAIM,
                        deferSecondaryIntent(session, ProcedureType.CLAIM, ref, null, itemReference, null, null, problemText),
                        ref.orderNumber(), startNanos);
            }
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"),
                    ref.orderNumber(), startNanos),
                    ambiguousItemMetadata(ref.orderNumber(), e));
        }
        session.recordFocusItem(item.getSku(), item.getProductName());
        return startClaimWithItem(session, ref, item, problemText, startNanos);
    }

    /**
     * Pass 2D-B cleanup: the shared tail of a claim start once the item is
     * resolved - eligibility, problem text, then the single procedure-entry
     * point. Used both by the model-driven start (free-text item resolution)
     * and by deferred promotion (strict scoped item-identity resolution,
     * never display text).
     */
    private ProcedureOutcome startClaimWithItem(ConversationSession session, VerifiedOrderRef ref, OrderItem item,
            String problemText, long startNanos) {
        session.recordFocusItem(item.getSku(), item.getProductName());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "Cannot file a claim against a cancelled order."), ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber(), "itemName", item.getProductName()));
        }
        if (problemText == null || problemText.isBlank()) {
            if (session.getActiveProcedure().isPresent()) {
                return recordOutcome(session, ProcedureType.CLAIM,
                        deferSecondaryIntent(session, ProcedureType.CLAIM, ref, item, null, null, null, null),
                        ref.orderNumber(), startNanos);
            }
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM, ProcedureOutcome.error("PROBLEM_REQUIRED",
                            "Could you tell me what happened with the " + item.getProductName() + " - for example was it damaged, defective, the wrong item, or missing?"),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber(), "itemName", item.getProductName()));
        }
        ClaimReason reason = parseClaimReason(problemText);
        Map<String, String> data = Map.of("itemReference", item.getSku(), "reason", reason.name(), "description", problemText);
        String description = "file a claim for " + item.getProductName() + " on order " + ref.orderNumber() + " (" + reason.name().toLowerCase(Locale.ROOT) + ")";
        ProcedureOutcome raw = beginProcedure(session, ProcedureType.CLAIM, ref, data, false, description, item.getProductName(), startNanos);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("orderReference", ref.orderNumber());
        extra.put("itemName", item.getProductName());
        extra.put("claimReason", reason.name());
        if ("CONFIRMATION_REQUIRED".equals(raw.code())) {
            // Customer-supplied words only: lets the model confirm what is
            // awaiting confirmation without relying on coordinator prose.
            extra.put("problemDescription", problemText);
        }
        return withMetadata(raw, extra);
    }

    /**
     * Escalation creates the support ticket through the repository's own
     * transaction (committed on {@code save()} return) and only then mutates
     * the in-memory session and records the durable audit event. There is
     * deliberately no outer transaction here: recording {@code ESCALATED} in
     * audit (which commits independently) or marking the session escalated
     * before the ticket row is committed would leave a false success trail if
     * the ticket insert later rolled back.
     */
    public ProcedureOutcome requestHumanSupport(ConversationSession session, String reason) {
        CustomerIdentity identity = session.getCustomerIdentity();
        if (!identity.isAtLeast(IdentityAssurance.PHONE_MATCHED)) {
            return ProcedureOutcome.error(
                    "IDENTITY_NOT_VERIFIED", "I can connect you with our team, but I'll need your registered phone number first so they can pull up your account.");
        }
        if (session.isEscalated()) {
            String existingTicket = session.getRecentActions().stream()
                    .filter(a -> a.type() == RecentActionType.ESCALATED)
                    .reduce((first, second) -> second)
                    .map(RecentAction::reference)
                    .orElse(null);
            ProcedureOutcome outcome = ProcedureOutcome.ok("ALREADY_ESCALATED", existingTicket != null
                    ? "You're already connected to our support team on ticket " + existingTicket + " - they have the details already."
                    : "You're already connected to our support team for this conversation.");
            return existingTicket != null
                    ? withMetadata(outcome, Map.of("ticketNumber", existingTicket))
                    : outcome;
        }
        Customer customer = customerRepository.findById(identity.requireCustomerId()).orElseThrow();
        String summary = buildHandoffSummary(session, reason);
        SupportTicket ticket = supportTicketRepository.save(new SupportTicket(
                referenceNumberGenerator.ticketNumber(), customer, null, TicketCategory.ESCALATION, TicketPriority.MEDIUM, summary));
        session.markEscalated();
        session.recordAction(new RecentAction(RecentActionType.ESCALATED, "SUPPORT", "OPEN", null, ticket.getTicketNumber(), Instant.now()));
        log.info("event=procedure_escalated ticketNumber={}", ticket.getTicketNumber());
        turnMetrics.recordProcedureOutcome("ESCALATION", "ESCALATED", true);
        auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.ESCALATED, "ticketNumber=" + ticket.getTicketNumber());
        return withMetadata(ProcedureOutcome.ok("ESCALATED", "I've let our support team know - reference " + ticket.getTicketNumber()
                + ". They'll have the details of what we've discussed so far."),
                Map.of("ticketNumber", ticket.getTicketNumber()));
    }

    // ---- Verification (called ONLY from ConversationRuntime, never from a tool) ----

    // No outer transaction here either: verificationService.verify() and the
    // domain service inside execute() each commit on return; the
    // EXECUTION_SUCCEEDED audit and session updates below therefore always
    // run after the domain state is durable.
    public ProcedureOutcome submitVerificationCode(ConversationSession session, String code) {
        long startNanos = System.nanoTime();
        ProcedureState procedure = session.getActiveProcedure().filter(p -> p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION).orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_VERIFICATION", "There's no verification code pending right now.");
        }
        String orderReference = procedure.getVerifiedTarget().orderNumber();
        VerificationResult result = verificationService.verify(session, code, procedure.getProcedureId(), orderReference);
        if (!result.verified()) {
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.OTP_FAILED, "type=" + procedure.getType() + " orderReference=" + orderReference);
            // Pass 2C (Task 7): the failure's stable reason travels as structured
            // metadata - the direct renderer keys off verificationReason instead
            // of parsing the English message.
            Map<String, String> reasonMetadata = new LinkedHashMap<>();
            reasonMetadata.put("verificationReason", result.code().name());
            reasonMetadata.put("orderReference", orderReference);
            return recordOutcome(session, procedure.getType(),
                    withMetadata(ProcedureOutcome.error("VERIFICATION_FAILED", result.message()), reasonMetadata), orderReference, startNanos);
        }
        auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.OTP_VERIFIED, "type=" + procedure.getType() + " orderReference=" + orderReference);

        ProcedureOutcome outcome;
        try {
            outcome = execute(session, procedure);
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.EXECUTION_SUCCEEDED,
                    "type=" + procedure.getType() + " code=" + outcome.code() + " orderReference=" + orderReference);
        } catch (RuntimeException e) {
            log.error("event=procedure_execution_failed type={} orderNumber={} errorType={}",
                    procedure.getType(), orderReference, e.getClass().getSimpleName(), e);
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            turnMetrics.recordProcedureOutcome(procedure.getType().name(), "EXECUTION_FAILED", false);
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.EXECUTION_FAILED,
                    "type=" + procedure.getType() + " errorType=" + e.getClass().getSimpleName() + " orderReference=" + orderReference);
            throw e;
        }
        procedure.setStatus(ProcedureStatus.EXECUTED);
        session.clearActiveProcedure();
        return recordOutcome(session, procedure.getType(), outcome, orderReference, startNanos);
    }

    public ProcedureOutcome resendVerificationCode(ConversationSession session) {
        ProcedureState procedure = session.getActiveProcedure().filter(p -> p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION).orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_VERIFICATION", "There's no verification in progress right now.");
        }
        String orderReference = procedure.getVerifiedTarget().orderNumber();
        VerificationOutcome outcome = verificationService.issueChallenge(session, purposeFor(procedure.getType()), procedure.getProcedureId(), orderReference);
        if (outcome.success()) {
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.OTP_ISSUED,
                    "type=" + procedure.getType() + " orderReference=" + orderReference + " resend=true");
        }
        return outcome.success()
                ? withMetadata(ProcedureOutcome.ok("VERIFICATION_REQUIRED", outcome.message(), outcome.metadata()),
                        verificationMetadata(orderReference, outcome))
                : withMetadata(ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", outcome.message()),
                        verificationMetadata(orderReference, outcome));
    }

    // ---- Advancing a pending confirmation (CLAIM only - called ONLY from ConversationRuntime, never from a tool) ----

    /**
     * Executes the pending confirmed procedure. The domain service call
     * ({@code cancellationService}/{@code returnService}/{@code claimService},
     * each {@code @Transactional}) commits when it returns; only afterwards
     * are the in-memory session state and the durable audit events
     * ({@code EXECUTION_SUCCEEDED}, {@code PROCEDURE_COMPLETED}) updated.
     * There is deliberately no outer transaction here: with one, the audit
     * service's independent commit would record success before the domain
     * transaction committed, leaving a false success trail on rollback.
     */
    public ProcedureOutcome confirmActive(ConversationSession session) {
        long startNanos = System.nanoTime();
        ProcedureState procedure = session.getActiveProcedure().orElse(null);
        if (procedure == null || procedure.getStatus() != ProcedureStatus.AWAITING_CONFIRMATION) {
            return ProcedureOutcome.error("NO_PENDING_CONFIRMATION", "There's nothing waiting for confirmation right now.");
        }
        String orderReference = procedure.getVerifiedTarget().orderNumber();
        PendingAction pendingAction = procedure.getPendingAction();
        if (pendingAction == null || Instant.now().isAfter(pendingAction.expiresAt())) {
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            return recordOutcome(session, procedure.getType(),
                    ProcedureOutcome.error("EXPIRED", "That request has expired - let's start again if you'd still like to go ahead."), orderReference, startNanos);
        }

        PolicyDecision recheck = actionPolicyService.evaluate(new ActionRequest(procedure.getType(), procedure.getRequiredAssurance(), true), session);
        if (recheck.outcome() != PolicyOutcome.ALLOW) {
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            return recordOutcome(session, procedure.getType(),
                    ProcedureOutcome.error("IDENTITY_NOT_VERIFIED", "This still needs identity verification we can't complete."), orderReference, startNanos);
        }

        ProcedureOutcome outcome;
        try {
            outcome = execute(session, procedure);
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.EXECUTION_SUCCEEDED,
                    "type=" + procedure.getType() + " code=" + outcome.code() + " orderReference=" + orderReference);
        } catch (RuntimeException e) {
            log.error("event=procedure_execution_failed type={} orderNumber={} errorType={}",
                    procedure.getType(), orderReference, e.getClass().getSimpleName(), e);
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            turnMetrics.recordProcedureOutcome(procedure.getType().name(), "EXECUTION_FAILED", false);
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.EXECUTION_FAILED,
                    "type=" + procedure.getType() + " errorType=" + e.getClass().getSimpleName() + " orderReference=" + orderReference);
            throw e;
        }
        procedure.setStatus(ProcedureStatus.EXECUTED);
        session.clearActiveProcedure();
        return recordOutcome(session, procedure.getType(), outcome, orderReference, startNanos);
    }

    public ProcedureOutcome declineActive(ConversationSession session) {
        long startNanos = System.nanoTime();
        ProcedureState procedure = session.getActiveProcedure().orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_CONFIRMATION", "There's nothing waiting for confirmation right now.");
        }
        String orderReference = procedure.getVerifiedTarget().orderNumber();
        procedure.setStatus(ProcedureStatus.CANCELLED);
        session.clearActiveProcedure();
        return recordOutcome(session, procedure.getType(), withMetadata(
                ProcedureOutcome.ok("DECLINED", "No problem, I won't go ahead with that."), Map.of("orderReference", orderReference)), orderReference, startNanos);
    }

    // ---- internal ----

    /**
     * Phase 10 gap-fix: every procedure outcome now records the attempted order reference and
     * elapsed duration alongside the type/code that was already there - this is what makes a
     * failed return/claim explainable from the Conversation Inspector instead of just "it failed."
     */
    private ProcedureOutcome recordOutcome(ConversationSession session, ProcedureType type, ProcedureOutcome outcome, String orderReference, long startNanos) {
        turnMetrics.recordProcedureOutcome(type.name(), outcome.code(), outcome.success());
        // P2: feed the turn decision trace. Terminal paths clear the active
        // procedure before recording, so the status is the live one when the
        // procedure is still active - otherwise it stays null (never guessed).
        TurnTrace.Builder traceBuilder = session.getActiveTraceBuilder();
        if (traceBuilder != null) {
            traceBuilder.procedureType(type.name());
            traceBuilder.procedureOutcomeCode(outcome.code());
            session.getActiveProcedure().ifPresent(p -> traceBuilder.procedureStatus(p.getStatus().name()));
        }
        ConversationEventType eventType = classifyProcedureEvent(outcome);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        StringBuilder detail = new StringBuilder("type=").append(type).append(" code=").append(outcome.code());
        if (orderReference != null && !orderReference.isBlank()) {
            detail.append(" orderReference=").append(orderReference);
        }
        detail.append(" durationMs=").append(durationMs);
        auditService.recordEvent(session, session.getTurnCount(), eventType, detail.toString());
        return outcome;
    }

    /**
     * Pass 2B (Task 4): attach support-safe structured facts to a coordinator
     * outcome at the source, so the model-facing mapper can carry them without
     * ever parsing {@code ProcedureOutcome.message()}.
     *
     * <p>Only whitelisted values are ever passed here: order references, item
     * product names, denial reasons, payment consequences, return/claim/ticket
     * numbers, and customer-supplied descriptions. Never SKUs, database IDs,
     * customer IDs, procedure IDs, verification challenge IDs, OTP material,
     * internal slot keys, or raw exception text.
     */
    private ProcedureOutcome withMetadata(ProcedureOutcome outcome, Map<String, String> additions) {
        if (additions == null || additions.isEmpty()) {
            return outcome;
        }
        Map<String, String> merged = new LinkedHashMap<>(outcome.metadata() == null ? Map.of() : outcome.metadata());
        additions.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key, value);
            }
        });
        return new ProcedureOutcome(outcome.success(), outcome.code(), outcome.message(), Map.copyOf(merged));
    }

    /**
     * Carries ambiguous item candidates as indexed keys so the model-facing
     * mapper can fold them into a single list of customer-visible product
     * names - no SKUs or item IDs.
     */
    private Map<String, String> ambiguousItemMetadata(String orderNumber, AmbiguousItemException e) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("orderReference", orderNumber);
        List<OrderItem> candidates = e.getCandidates();
        for (int i = 0; i < Math.min(candidates.size(), 5); i++) {
            md.put("candidateItem." + (i + 1), candidates.get(i).getProductName());
        }
        return md;
    }

    /**
     * Pass 2C (Task 7): carries a challenge issue/rate-limit outcome into the
     * coordinator outcome as stable, safe structured facts. The dev OTP is
     * deliberately NOT copied here - only the masked destination and the
     * stable issue code travel beyond the verification layer.
     */
    private static Map<String, String> verificationMetadata(String orderNumber, VerificationOutcome outcome) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("verificationIssue", outcome.issueCode().name());
        md.put("orderReference", orderNumber);
        if (outcome.metadata() != null) {
            String masked = outcome.metadata().get("maskedDestination");
            if (masked != null) {
                md.put("maskedDestination", masked);
            }
        }
        return md;
    }

    private static Map<String, String> cancellationMetadata(String orderNumber, CancellationEligibility eligibility) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("orderReference", orderNumber);
        if (eligibility.denialReason() != null) {
            md.put("denialReason", eligibility.denialReason().name());
        }
        if (eligibility.paymentConsequence() != null) {
            md.put("paymentConsequence", eligibility.paymentConsequence().name());
        }
        return md;
    }

    private static Map<String, String> returnDenialMetadata(String orderNumber, String itemName, ReturnEligibility eligibility) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("orderReference", orderNumber);
        md.put("itemName", itemName);
        md.put("denialReason", eligibility.denialReason().name());
        md.put("maxReturnableQuantity", Integer.toString(eligibility.maxReturnableQuantity()));
        return md;
    }

    private ConversationEventType classifyProcedureEvent(ProcedureOutcome outcome) {
        if (ProcedureOutcome.DEFERRED_CODES.contains(outcome.code())) {
            return ConversationEventType.PROCEDURE_DEFERRED;
        }
        if (!outcome.success()) {
            // Clarification states (missing item/reason/problem) are not real
            // failures - they get their own audit event so failure timelines
            // and the Conversation Inspector stop crying wolf.
            return ProcedureOutcome.CLARIFICATION_CODES.contains(outcome.code())
                    ? ConversationEventType.PROCEDURE_CLARIFICATION
                    : ConversationEventType.PROCEDURE_FAILED;
        }
        return STARTED_CODES.contains(outcome.code()) ? ConversationEventType.PROCEDURE_STARTED : ConversationEventType.PROCEDURE_COMPLETED;
    }

    private VerifiedOrderRef resolveOrder(ConversationSession session, String orderReference) {
        try {
            return ownedOrderResolver.resolve(session.getCustomerIdentity(), orderReference);
        } catch (ResourceNotFoundForAccountException e) {
            return null;
        }
    }

    private ProcedureOutcome beginProcedure(
            ConversationSession session, ProcedureType type, VerifiedOrderRef target, Map<String, String> data,
            boolean requiresOtp, String actionDescription, String itemDisplayName, long startNanos) {

        if (!session.getCustomerIdentity().isAtLeast(IdentityAssurance.PHONE_MATCHED)) {
            return recordOutcome(session, type,
                    ProcedureOutcome.error("IDENTITY_NOT_VERIFIED", "I'll need to verify who I'm speaking with before I can do that."), target.orderNumber(), startNanos);
        }

        // Pass 2D-B: exactly one live procedure per session. A repeat of the
        // identical request reuses the pending state (no second OTP, no
        // second confirmation, no state mutation); a genuinely different
        // mutation request becomes one deferred intent with no authorization
        // of its own. A second live ProcedureState is impossible by
        // construction - the session has no slot for it.
        ProcedureRequestFingerprint request = ProcedureRequestFingerprint.ofRequest(type, target, data);
        Optional<ProcedureState> active = session.getActiveProcedure();
        if (active.isPresent()) {
            // Pass 2D-B dedupe fix: asymmetric compatibility, not strict
            // fingerprint equality. An incomplete repeat of the active request
            // (fields the new request does not supply are "unspecified") is
            // the same request: the live procedure is reused untouched. For
            // fully specified requests this is exactly strict equality.
            if (isCompatibleRepeat(activeAsIntent(active.get()), requestAsIntent(type, target, data, itemDisplayName))) {
                return recordOutcome(session, type, alreadyPendingOutcome(active.get(), target), target.orderNumber(), startNanos);
            }
            return recordOutcome(session, type, deferRequest(session, type, target, data, itemDisplayName), target.orderNumber(), startNanos);
        }

        // Pass 2D-B: the freed slot is contested - a deferred intent is
        // still queued behind no active procedure (e.g. the active one was
        // just abandoned). Starting a genuinely different request here would
        // silently strand the queued intent; starting the queued request
        // itself a second time would double-file it on promotion. So a
        // different request is refused without mutation and the customer
        // chooses, while the queued request itself is consumed and started
        // fresh right now.
        Optional<DeferredProcedureIntent> deferred = session.getDeferredIntent();
        if (deferred.isPresent()) {
            DeferredProcedureIntent queued = deferred.get();
            if (queued.fingerprint().equals(request)) {
                session.clearDeferredIntent();
                log.info("event=deferred_intent_started_now type={} orderNumber={}", type, target.orderNumber());
            } else if (isCompatibleRepeat(queued, requestAsIntent(type, target, data, itemDisplayName))) {
                // Pass 2D-B dedupe fix: a compatible repeat of the queued
                // intent is the same queued request - it stays queued,
                // untouched, with no new authorization.
                log.info("event=deferred_compatible_repeat type={} orderNumber={}", type, target.orderNumber());
                return recordOutcome(session, type,
                        withMetadata(ProcedureOutcome.ok("ALREADY_DEFERRED", alreadyQueuedMessage(session)),
                                Map.of("orderReference", target.orderNumber())),
                        target.orderNumber(), startNanos);
            } else {
                log.info("event=deferred_request_conflict type={} orderNumber={}", type, target.orderNumber());
                return recordOutcome(session, type,
                        withMetadata(ProcedureOutcome.error("DEFERRED_REQUEST_PENDING",
                                        // Phase 6: no active procedure exists on this path, so
                                        // "do this afterwards" would be wrong - there is nothing
                                        // to do it after. The customer picks between the queued
                                        // request and the new one.
                                        "There's already a " + describeIntent(queued)
                                                + " queued. Should I start the queued one instead, or drop it and go ahead with this?"),
                                Map.of("orderReference", target.orderNumber())),
                        target.orderNumber(), startNanos);
            }
        }

        ProcedureState procedure = new ProcedureState(type, target, data, requiresOtp ? IdentityAssurance.OTP_VERIFIED : IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription(actionDescription);
        session.startActiveProcedure(procedure);

        if (requiresOtp) {
            procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
            VerificationOutcome verificationOutcome = verificationService.issueChallenge(session, purposeFor(type), procedure.getProcedureId(), target.orderNumber());
            if (!verificationOutcome.success()) {
                procedure.setStatus(ProcedureStatus.FAILED);
                session.clearActiveProcedure();
                return recordOutcome(session, type,
                        withMetadata(ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", verificationOutcome.message()),
                                verificationMetadata(target.orderNumber(), verificationOutcome)),
                        target.orderNumber(), startNanos);
            }
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.OTP_ISSUED, "type=" + type + " orderReference=" + target.orderNumber());
            return recordOutcome(session, type,
                    withMetadata(ProcedureOutcome.ok("VERIFICATION_REQUIRED", verificationOutcome.message()),
                            verificationMetadata(target.orderNumber(), verificationOutcome)),
                    target.orderNumber(), startNanos);
        }

        return recordOutcome(session, type, moveToConfirmation(procedure), target.orderNumber(), startNanos);
    }

    /**
     * Pass 2D-B: the exact same mutation was requested again while it is
     * still pending. Reuse the live procedure untouched: same pending
     * challenge, same confirmation, zero new OTP issuance, zero state
     * mutation. The {@code pendingStage} metadata lets the model-facing
     * mapper preserve the correct next action (ask for the code vs. ask for
     * confirmation).
     */
    private ProcedureOutcome alreadyPendingOutcome(ProcedureState active, VerifiedOrderRef target) {
        String pendingStage = active.getStatus() == ProcedureStatus.AWAITING_VERIFICATION
                ? "VERIFICATION_REQUIRED"
                : "CONFIRMATION_REQUIRED";
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("orderReference", target.orderNumber());
        metadata.put("pendingStage", pendingStage);
        log.info("event=procedure_duplicate_request type={} orderNumber={} stage={}", active.getType(), target.orderNumber(), pendingStage);
        return withMetadata(ProcedureOutcome.ok("ALREADY_PENDING",
                "That request is already in progress - nothing new was started."), metadata);
    }

    /**
     * Pass 2D-B: a genuinely different mutation was requested while one
     * procedure is live. It is stored as the single deferred intent - no OTP,
     * no confirmation, no procedure state, no authorization - or, if the
     * deferred slot is already occupied, the request is refused with a
     * choice-required outcome. Nothing already queued is ever overwritten or
     * silently dropped.
     */
    private ProcedureOutcome deferRequest(
            ConversationSession session, ProcedureType type, VerifiedOrderRef target,
            Map<String, String> data, String itemDisplayName) {
        return deferIntent(session, DeferredProcedureIntent.of(type, target, data, itemDisplayName));
    }

    /**
     * Pass 2D-B final cleanup: defers a secondary mutation request that
     * arrived while another procedure is active, capturing whatever fields
     * are currently known even when item / reason / problem / quantity is
     * still missing. The deferred slots are never collected immediately -
     * the customer is not interrogated about the queued request now - and
     * no second live {@code ProcedureState} is started. Ownership, item
     * identity, and eligibility are freshly re-resolved when the intent is
     * promoted; a fresh OTP / fresh confirmation is required then.
     *
     * @param resolvedItem the item when it was already resolved, else null
     * @param itemReference the raw customer-supplied item reference, if any
     * @param reasonText the raw return reason text, if any
     * @param quantityText the raw return quantity text, if any
     * @param problemText the raw claim problem text, if any
     */
    private ProcedureOutcome deferSecondaryIntent(
            ConversationSession session, ProcedureType type, VerifiedOrderRef ref, OrderItem resolvedItem,
            String itemReference, String reasonText, String quantityText, String problemText) {
        String itemDisplayName = resolvedItem != null ? resolvedItem.getProductName() : blankToNull(itemReference);
        String itemSku = resolvedItem != null ? resolvedItem.getSku() : null;
        String reasonName = null;
        if (type == ProcedureType.RETURN && !isBlank(reasonText)) {
            reasonName = parseReturnReason(reasonText).name();
        } else if (type == ProcedureType.CLAIM && !isBlank(problemText)) {
            reasonName = parseClaimReason(problemText).name();
        }
        String quantity = type == ProcedureType.RETURN
                ? blankToNull(quantityText)
                : ProcedureRequestFingerprint.defaultQuantity();
        String detail = type == ProcedureType.CLAIM ? blankToNull(problemText) : null;
        DeferredProcedureIntent candidate = DeferredProcedureIntent.incomplete(
                type, ref, itemDisplayName, itemSku, reasonName, quantity, detail);
        // Pass 2D-B dedupe fix: an incomplete repeat of the already-active
        // request is the same request (ALREADY_PENDING) - the active
        // procedure stays authoritative and untouched. Only a genuinely
        // different request is queued behind it.
        Optional<ProcedureState> active = session.getActiveProcedure();
        if (active.isPresent() && isCompatibleRepeat(activeAsIntent(active.get()), candidate)) {
            log.info("event=procedure_compatible_repeat_pending type={} orderNumber={}", type, ref.orderNumber());
            return alreadyPendingOutcome(active.get(), ref);
        }
        return deferIntent(session, candidate);
    }

    /**
     * Pass 2D-B: stores the candidate as the single deferred intent, with
     * duplicate detection against the already-queued intent. A repeat of the
     * queued request is {@code ALREADY_DEFERRED}; a genuinely different
     * request while the slot is occupied is
     * {@code PENDING_REQUEST_LIMIT_REACHED} - the queued request is never
     * overwritten or silently dropped.
     */
    private ProcedureOutcome deferIntent(ConversationSession session, DeferredProcedureIntent candidate) {
        Optional<DeferredProcedureIntent> existing = session.getDeferredIntent();
        if (existing.isPresent()) {
            // Pass 2D-B dedupe fix: asymmetric compatibility, not strict
            // fingerprint equality. An incomplete repeat of the queued
            // request is the same queued request.
            if (isCompatibleRepeat(existing.get(), candidate)) {
                log.info("event=procedure_duplicate_deferred type={} orderNumber={}", candidate.type(), candidate.orderNumber());
                return withMetadata(ProcedureOutcome.ok("ALREADY_DEFERRED", alreadyQueuedMessage(session)),
                        Map.of("orderReference", candidate.orderNumber()));
            }
            String activeDesc = session.getActiveProcedure().map(this::describe).orElse("one request");
            log.info("event=procedure_queue_full type={} orderNumber={}", candidate.type(), candidate.orderNumber());
            return withMetadata(ProcedureOutcome.error("PENDING_REQUEST_LIMIT_REACHED",
                            "There's already a " + activeDesc + " in progress and a " + describeIntent(existing.get())
                                    + " queued behind it. Which one would you like to keep?"),
                    Map.of("orderReference", candidate.orderNumber()));
        }
        session.setDeferredIntent(candidate);
        String activeDesc = session.getActiveProcedure().map(this::describe).orElse("one request");
        log.info("event=procedure_deferred type={} orderNumber={}", candidate.type(), candidate.orderNumber());
        return withMetadata(ProcedureOutcome.ok("PROCEDURE_DEFERRED",
                        "Noted - I'll take care of that right after the current " + activeDesc + "."),
                Map.of("orderReference", candidate.orderNumber()));
    }

    /**
     * Pass 2D-B dedupe fix: asymmetric compatibility between an existing
     * request (the active procedure or the queued deferred intent, expressed
     * as an intent view) and a new candidate request. The candidate is a
     * compatible repeat - the SAME request - when:
     * <ul>
     *   <li>the procedure type matches,</li>
     *   <li>the order matches,</li>
     *   <li>every stable field the candidate actually supplies (resolved
     *       item, parsed reason, quantity) equals the existing request.</li>
     * </ul>
     * A field the candidate does not supply means "unspecified", never
     * "different". An explicitly different supplied value - a different
     * resolved item, a different parsed reason, a different quantity - is
     * always a distinct request. Raw free-text detail is payload, never
     * identity.
     *
     * <p>This comparison is ONLY for deduplication. It never copies missing
     * fields into either request, never mutates the active
     * {@code ProcedureState}, never inherits OTP/confirmation authority, and
     * never changes the stored reason/quantity/description: the existing
     * request remains authoritative and the candidate's unsupplied fields are
     * simply ignored. {@link ProcedureRequestFingerprint} equality itself is
     * deliberately left strict and untouched.
     */
    private static boolean isCompatibleRepeat(DeferredProcedureIntent existing, DeferredProcedureIntent candidate) {
        if (existing.type() != candidate.type()) {
            return false;
        }
        if (!existing.orderId().equals(candidate.orderId())) {
            return false;
        }
        if (!itemCompatible(existing, candidate)) {
            return false;
        }
        if (!fieldCompatible(existing.quantity(), candidate.quantity())) {
            return false;
        }
        return fieldCompatible(existing.reasonName(), candidate.reasonName());
    }

    /**
     * A candidate field that was not supplied is "unspecified" and can never
     * make two requests distinct; a supplied field must equal the existing
     * request's value.
     */
    private static boolean fieldCompatible(String existingValue, String candidateValue) {
        return candidateValue == null || Objects.equals(existingValue, candidateValue);
    }

    /**
     * Item identity for the compatibility check. A resolved SKU decides when
     * the candidate carries one. Otherwise a candidate that named an item
     * without resolving it must textually match the existing request's item -
     * an unresolved different name is a different request, never a silent
     * match. Naming no item at all means "unspecified".
     */
    private static boolean itemCompatible(DeferredProcedureIntent existing, DeferredProcedureIntent candidate) {
        if (candidate.itemSku() == null && candidate.itemDisplayName() == null) {
            return true;
        }
        if (candidate.itemSku() != null) {
            return Objects.equals(existing.itemSku(), candidate.itemSku());
        }
        return normalizedEquals(existing.itemDisplayName(), candidate.itemDisplayName());
    }

    /**
     * Intent-shaped view of the currently active procedure, for dedupe
     * comparison only. Never authoritative: it carries no OTP, no challenge,
     * no confirmation state, and no execution authority.
     */
    private DeferredProcedureIntent activeAsIntent(ProcedureState active) {
        VerifiedOrderRef target = active.getVerifiedTarget();
        Map<String, String> data = active.getCollectedData();
        String itemSku = data.get("itemReference");
        return DeferredProcedureIntent.incomplete(
                active.getType(),
                target,
                itemSku == null ? null : resolveActiveItemDisplayName(target, itemSku),
                itemSku,
                data.get("reason"),
                data.getOrDefault("quantity", ProcedureRequestFingerprint.defaultQuantity()),
                data.get("description"));
    }

    /**
     * Best-effort product name for the active procedure's item, used only so
     * an unresolved candidate item name can be compared textually. Null when
     * it cannot be established - an unresolvable name then stays distinct,
     * which is the safe direction.
     */
    private String resolveActiveItemDisplayName(VerifiedOrderRef target, String itemSku) {
        try {
            return ownedOrderItemResolver.resolveBySku(target, itemSku).getProductName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Intent-shaped view of a fully validated new request, for dedupe
     * comparison only.
     */
    private static DeferredProcedureIntent requestAsIntent(
            ProcedureType type, VerifiedOrderRef target, Map<String, String> data, String itemDisplayName) {
        return DeferredProcedureIntent.incomplete(
                type,
                target,
                itemDisplayName,
                data.get("itemReference"),
                data.get("reason"),
                data.getOrDefault("quantity", ProcedureRequestFingerprint.defaultQuantity()),
                data.get("description"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private static boolean normalizedEquals(String first, String second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return first.strip().equalsIgnoreCase(second.strip());
    }

    /**
     * Pass 2D-B: promotes the deferred intent after the active procedure
     * reached a terminal state. Promotion is a brand-new procedure start:
     * <ol>
     *   <li>ownership is re-resolved and business eligibility re-checked by
     *       the normal start paths - a stale eligibility verdict is never
     *       trusted;</li>
     *   <li>no previous authorization is reused: cancellation/return get a
     *       fresh OTP challenge, claims require fresh explicit
     *       confirmation.</li>
     * </ol>
     * If the action is no longer eligible, the authoritative NOT_ELIGIBLE
     * outcome is returned and nothing is executed.
     *
     * <p>Pass 2D-B final cleanup - explicit deferred-consumption semantics.
     * The intent is cleared optimistically before the attempt and consumed
     * exactly once when the promotion reaches an authoritative
     * {@link ProcedureOutcome} <em>for this intent</em>:
     * <ul>
     *   <li>consumed: VERIFICATION_REQUIRED / CONFIRMATION_REQUIRED (a fresh
     *       live procedure now owns the slot), NOT_ELIGIBLE /
     *       NOT_FOUND_FOR_ACCOUNT (terminal refusal), ITEM_REQUIRED /
     *       REASON_REQUIRED / PROBLEM_REQUIRED / QUANTITY_REQUIRED
     *       (authoritative clarification - the customer answers on the next
     *       turn as a fresh request, exactly like the model-driven path);</li>
     *   <li>restored: any unexpected technical exception (no authoritative
     *       decision was reached - the queued request stays queued). A
     *       technical failure must never lose the queued customer request,
     *       so the original intent is put back even when a partial live
     *       procedure was created and dropped before the failure.</li>
     * </ul>
     * An unexpected exception can never mask, roll back semantically, or
     * misreport the already-completed active mutation: the caller keeps the
     * successful result and additionally receives a safe PROMOTION_FAILED
     * outcome. A partially-created live procedure never survives such a
     * failure (any OTP challenge issued for it is invalidated), and the
     * restored intent carries no OTP/challenge/confirmation material, so the
     * next promotion still starts fully fresh.
     *
     * <p>All session mutations here run under the per-session lock held by
     * {@code SessionStore.withSession}, so the read-attempt-consume sequence
     * is atomic with respect to other turns.
     *
     * <p>This is a no-op unless the active slot is empty and a deferred
     * intent exists, so callers can invoke it unconditionally after terminal
     * transitions. Explicit abandonment deliberately does NOT promote: the
     * customer's next stated request takes the freed slot instead.
     */
    public Optional<ProcedureOutcome> promoteDeferredIntent(ConversationSession session) {
        if (session.getActiveProcedure().isPresent()) {
            return Optional.empty();
        }
        DeferredProcedureIntent intent = session.getDeferredIntent().orElse(null);
        if (intent == null) {
            return Optional.empty();
        }
        // Pass 2D-B final cleanup: the intent is cleared before the attempt.
        // Clearing first is safe because the catch below restores the
        // original intent on ANY unexpected failure - a technical failure can
        // never silently destroy the queued customer request. It also lets
        // the normal start path consume the slot through beginProcedure for
        // fully and partially captured intents alike.
        session.clearDeferredIntent();
        ProcedureOutcome outcome;
        try {
            outcome = switch (intent.type()) {
                case CANCELLATION -> startCancellation(session, intent.orderNumber());
                // The parsed reason enum name re-parses deterministically and
                // the stored quantity is re-validated against current policy.
                // Fully captured items resolve by retained scoped identity,
                // never by customer-visible display text; partially captured
                // intents re-run the normal validation path from the raw
                // captured fields.
                case RETURN -> promoteReturn(session, intent);
                case CLAIM -> promoteClaim(session, intent);
            };
        } catch (RuntimeException e) {
            // Unexpected technical failure while promoting: the completed
            // active mutation stays reported as successful (the caller keeps
            // its result), and no partially-built live procedure may survive.
            log.warn("event=deferred_promotion_failed type={} orderNumber={} errorType={}",
                    intent.type(), intent.orderNumber(), e.getClass().getSimpleName());
            if (session.getActiveProcedure().isPresent()) {
                // A live procedure was created before the failure - it never
                // completed OTP/confirmation setup, so drop it and invalidate
                // any challenge issued for it. The invalidation touches the
                // database and may itself fail; the partial live state is
                // dropped regardless so it can never survive.
                try {
                    verificationService.invalidatePendingChallenge(session);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("event=promotion_partial_challenge_cleanup_failed errorType={}",
                            cleanupFailure.getClass().getSimpleName());
                }
                session.clearPendingVerification();
                session.clearActiveProcedure();
            }
            // The queued customer request must never be lost to a technical
            // failure: restore the original intent. It is structurally
            // incapable of carrying authorization, so the retry still gets a
            // fresh OTP / fresh confirmation.
            session.setDeferredIntent(intent);
            try {
                auditService.recordEvent(session, session.getTurnCount(),
                        ConversationEventType.DEFERRED_PROMOTION_FAILED,
                        "type=" + intent.type() + " orderReference=" + intent.orderNumber());
            } catch (RuntimeException auditFailure) {
                // Auditing must never turn a contained promotion failure into
                // a turn-level failure.
                log.warn("event=deferred_promotion_audit_failed errorType={}",
                        auditFailure.getClass().getSimpleName());
            }
            return Optional.of(ProcedureOutcome.error("PROMOTION_FAILED",
                    "The queued request could not be started."));
        }
        log.info("event=deferred_intent_promoted type={} orderNumber={} outcome={}",
                intent.type(), intent.orderNumber(), outcome.code());
        return Optional.of(outcome);
    }

    /**
     * Pass 2D-B cleanup: promotion-time return start. Ownership is
     * re-resolved from the customer-visible order reference, then the
     * retained internal item identity is resolved strictly inside the
     * freshly verified owned order. Display-name matching, single-item
     * shortcuts, and conversation-focus fallbacks are deliberately NOT
     * consulted: if the exact item no longer exists on (or belongs to) the
     * customer's order, promotion refuses with ITEM_REQUIRED instead of
     * substituting a different item. The stored SKU is an identity hint,
     * never authorization - eligibility is re-checked and a fresh OTP is
     * issued by the shared start tail.
     *
     * <p>Pass 2D-B final cleanup: an intent captured before the item was
     * resolved re-runs the normal validation path from the raw captured
     * fields, returning the same clarification outcomes
     * ({@code ITEM_REQUIRED} / {@code REASON_REQUIRED} /
     * {@code QUANTITY_REQUIRED}) the model-driven path would return.
     */
    private ProcedureOutcome promoteReturn(ConversationSession session, DeferredProcedureIntent intent) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, intent.orderNumber());
        if (ref == null) {
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."),
                    intent.orderNumber(), startNanos),
                    Map.of("orderReference", intent.orderNumber()));
        }
        session.recordFocusOrder(ref.orderNumber());
        if (intent.itemSku() == null) {
            // Partially captured at deferral time: re-validate from the raw
            // customer-supplied fields. The active slot is empty here, so
            // this returns clarification outcomes, never another deferral.
            return startReturnForOrder(session, ref, intent.itemDisplayName(), intent.reasonName(), intent.quantity(), startNanos);
        }
        OrderItem item;
        try {
            item = resolvePromotedItem(ref, intent.itemSku());
        } catch (ResourceNotFoundForAccountException e) {
            return withMetadata(recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("ITEM_REQUIRED", "The queued request's item is no longer on this order."),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber()));
        }
        return startReturnWithItem(session, ref, item, intent.reasonName(), intent.quantity(), startNanos);
    }

    /**
     * Pass 2D-B cleanup: promotion-time claim start. Same strict scoped
     * item-identity resolution as {@link #promoteReturn}: the retained SKU
     * selects the originally intended item inside the freshly verified owned
     * order, never an ambiguous display-name match. A fresh explicit
     * confirmation is required by the shared start tail.
     *
     * <p>Pass 2D-B final cleanup: an intent captured before the item or the
     * problem description was known re-runs the normal validation path from
     * the raw captured fields, returning the same clarification outcomes
     * ({@code ITEM_REQUIRED} / {@code PROBLEM_REQUIRED}) the model-driven
     * path would return.
     */
    private ProcedureOutcome promoteClaim(ConversationSession session, DeferredProcedureIntent intent) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, intent.orderNumber());
        if (ref == null) {
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."),
                    intent.orderNumber(), startNanos),
                    Map.of("orderReference", intent.orderNumber()));
        }
        session.recordFocusOrder(ref.orderNumber());
        if (intent.itemSku() == null) {
            // Partially captured at deferral time: re-validate from the raw
            // customer-supplied fields. The active slot is empty here, so
            // this returns clarification outcomes, never another deferral.
            return startClaimForOrder(session, ref, intent.itemDisplayName(), intent.detail(), startNanos);
        }
        OrderItem item;
        try {
            item = resolvePromotedItem(ref, intent.itemSku());
        } catch (ResourceNotFoundForAccountException e) {
            return withMetadata(recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("ITEM_REQUIRED", "The queued request's item is no longer on this order."),
                    ref.orderNumber(), startNanos),
                    Map.of("orderReference", ref.orderNumber()));
        }
        return startClaimWithItem(session, ref, item, intent.detail(), startNanos);
    }

    /**
     * Resolves the retained promotion-time item identity strictly inside the
     * freshly verified owned order. Never falls back to display-name
     * matching: with two identically-named items on the order, only the
     * exact stored identity selects the originally intended item.
     */
    private OrderItem resolvePromotedItem(VerifiedOrderRef ref, String itemSku) {
        if (itemSku == null || itemSku.isBlank()) {
            throw new ResourceNotFoundForAccountException("ORDER_ITEM", String.valueOf(itemSku));
        }
        return ownedOrderItemResolver.resolveBySku(ref, itemSku);
    }

    /**
     * Pass 2D-B: safe abandonment of the currently pending procedure. This
     * is a control operation, not a commerce mutation: it drops the pending
     * request, invalidates any OTP challenge tied to it (the old code can
     * never authorize anything afterwards), and clears pending confirmation
     * authority. Completed DB state is untouched, and the deferred intent -
     * if any - is deliberately left queued (no silent promotion, no silent
     * drop).
     */
    public ProcedureOutcome abandonActiveProcedure(ConversationSession session) {
        long startNanos = System.nanoTime();
        ProcedureState active = session.getActiveProcedure().orElse(null);
        if (active == null) {
            return ProcedureOutcome.error("NO_ACTIVE_PROCEDURE", "There's no pending action to abandon right now.");
        }
        String orderReference = active.getVerifiedTarget().orderNumber();
        verificationService.invalidatePendingChallenge(session);
        session.clearPendingVerification();
        active.setStatus(ProcedureStatus.CANCELLED);
        session.clearActiveProcedure();
        log.info("event=procedure_abandoned type={} orderNumber={}", active.getType(), orderReference);
        // Phase 6: a queued request deliberately survives abandonment (no
        // silent promotion, no silent drop) - but the customer must be told
        // it is still there, otherwise the queue goes silent and the request
        // is effectively lost to them.
        String message = "The pending request has been dropped - nothing was executed.";
        Optional<DeferredProcedureIntent> queued = session.getDeferredIntent();
        if (queued.isPresent()) {
            message += " Your " + describeIntent(queued.get())
                    + " is still queued - just say the word and I'll start it, or I can drop it too.";
        }
        return recordOutcome(session, active.getType(), withMetadata(
                ProcedureOutcome.ok("ACTIVE_PROCEDURE_ABANDONED", message),
                Map.of("orderReference", orderReference)), orderReference, startNanos);
    }

    /**
     * Pass 2D-B: discards only the queued deferred intent. Never touches the
     * active procedure, never executes anything, never issues OTP.
     */
    public ProcedureOutcome discardDeferredIntent(ConversationSession session) {
        long startNanos = System.nanoTime();
        DeferredProcedureIntent intent = session.getDeferredIntent().orElse(null);
        if (intent == null) {
            return ProcedureOutcome.error("NO_DEFERRED_INTENT", "There's no queued request to discard.");
        }
        session.clearDeferredIntent();
        log.info("event=deferred_intent_discarded type={} orderNumber={}", intent.type(), intent.orderNumber());
        return recordOutcome(session, intent.type(), withMetadata(
                ProcedureOutcome.ok("DEFERRED_INTENT_DISCARDED", "The queued request has been discarded."),
                Map.of("orderReference", intent.orderNumber())), intent.orderNumber(), startNanos);
    }

    private ProcedureOutcome moveToConfirmation(ProcedureState procedure) {
        PendingAction pendingAction = new PendingAction(
                UUID.randomUUID(), procedure.getType(), procedure.getVerifiedTarget(), procedure.getCollectedData(),
                Instant.now(), Instant.now().plus(PENDING_ACTION_TTL), UUID.randomUUID().toString());
        procedure.setPendingAction(pendingAction);
        procedure.setStatus(ProcedureStatus.AWAITING_CONFIRMATION);
        return ProcedureOutcome.ok("CONFIRMATION_REQUIRED", "Just to confirm - you'd like to " + procedure.getPendingDescription() + "?");
    }

    private VerificationPurpose purposeFor(ProcedureType type) {
        return switch (type) {
            case CANCELLATION -> VerificationPurpose.CANCELLATION;
            case RETURN -> VerificationPurpose.RETURN;
            case CLAIM -> throw new IllegalStateException("CLAIM never requires OTP verification");
        };
    }

    private ProcedureOutcome execute(ConversationSession session, ProcedureState procedure) {
        return switch (procedure.getType()) {
            case CANCELLATION -> executeCancellation(session, procedure);
            case RETURN -> executeReturn(session, procedure);
            case CLAIM -> executeClaim(session, procedure);
        };
    }

    private ProcedureOutcome executeCancellation(ConversationSession session, ProcedureState procedure) {
        var result = cancellationService.cancel(procedure.getVerifiedTarget());
        // FIX: the payment consequence is now part of the stable historical fact, not just
        // "cancelled" - this is what stops a later "will I get a refund?" turn from contradicting
        // what was just correctly stated (observed: an authorization-only order was told "you'll
        // get a refund in a few days" one turn after correctly saying nothing needed refunding).
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, result.orderNumber(),
                result.paymentConsequence().name(), null, result.orderNumber(), Instant.now()));
        if (result.refund() != null) {
            session.recordAction(new RecentAction(
                    RecentActionType.REFUND_INITIATED, result.orderNumber(), result.refund().getStatus().name(),
                    result.refund().getAmount(), result.refund().getRefundNumber(), Instant.now()));
        }
        return withMetadata(ProcedureOutcome.ok("CANCELLED", "Order " + result.orderNumber() + " has been cancelled."
                        + describePaymentConsequence(result.paymentConsequence())),
                Map.of("orderReference", result.orderNumber(), "paymentConsequence", result.paymentConsequence().name()));
    }

    private ProcedureOutcome executeReturn(ConversationSession session, ProcedureState procedure) {
        Map<String, String> data = procedure.getCollectedData();
        ReturnReason reason = ReturnReason.valueOf(data.get("reason"));
        int quantity = Integer.parseInt(data.getOrDefault("quantity", ProcedureRequestFingerprint.defaultQuantity()));
        var returnRequest = returnService.requestReturn(procedure.getVerifiedTarget(), data.get("itemReference"), quantity, reason);
        session.recordAction(new RecentAction(
                RecentActionType.RETURN_REQUESTED, procedure.getVerifiedTarget().orderNumber(), returnRequest.getStatus().name(),
                null, returnRequest.getReturnNumber(), Instant.now()));
        return withMetadata(ProcedureOutcome.ok("RETURN_STARTED", "Return " + returnRequest.getReturnNumber() + " has been started for order "
                        + procedure.getVerifiedTarget().orderNumber() + ". It is now requested and awaiting approval."),
                Map.of("orderReference", procedure.getVerifiedTarget().orderNumber(), "returnNumber", returnRequest.getReturnNumber()));
    }


    private ProcedureOutcome executeClaim(ConversationSession session, ProcedureState procedure) {
        Map<String, String> data = procedure.getCollectedData();
        ClaimReason reason = ClaimReason.valueOf(data.get("reason"));
        var claim = claimService.fileClaim(procedure.getVerifiedTarget(), data.get("itemReference"), reason, ClaimResolution.MANUAL_REVIEW, data.get("description"));
        session.recordAction(new RecentAction(
                RecentActionType.CLAIM_FILED, procedure.getVerifiedTarget().orderNumber(), claim.getStatus().name(), null, claim.getClaimNumber(), Instant.now()));
        return withMetadata(ProcedureOutcome.ok("CLAIM_FILED", "I've filed claim " + claim.getClaimNumber() + " for order "
                        + procedure.getVerifiedTarget().orderNumber() + " and opened a support ticket - our team will review it."),
                Map.of("orderReference", procedure.getVerifiedTarget().orderNumber(), "claimNumber", claim.getClaimNumber()));
    }

    private String describePaymentConsequence(PaymentConsequence consequence) {
        return switch (consequence) {
            case NO_REFUND_REQUIRED -> " No payment was collected, so there's nothing to refund.";
            case VOID_AUTHORIZATION -> " Since the payment was only authorized and not captured, there's nothing to refund.";
            case REFUND_REQUIRED -> " A refund will be issued for the full amount.";
            case MANUAL_REVIEW_REQUIRED -> " The payment on this order needs manual review before a refund decision can be made.";
        };
    }

    private String describe(ProcedureState procedure) {
        return procedure.getType().name().toLowerCase(Locale.ROOT) + " on order " + procedure.getVerifiedTarget().orderNumber();
    }

    /** Pass 2D-B: customer-visible description of a deferred intent - order reference and product name only. */
    private String describeIntent(DeferredProcedureIntent intent) {
        String base = intent.type().name().toLowerCase(Locale.ROOT) + " on order " + intent.orderNumber();
        return intent.itemDisplayName() != null ? base + " (" + intent.itemDisplayName() + ")" : base;
    }

    /**
     * Phase 6: the already-queued wording depends on whether a procedure is
     * still active. "Behind the current one" is only true while the active
     * procedure is live; on the contested-slot path the active procedure is
     * gone (e.g. just abandoned) and the request is simply queued.
     */
    private String alreadyQueuedMessage(ConversationSession session) {
        return session.getActiveProcedure().isPresent()
                ? "That request is already queued behind the current one."
                : "That request is already queued.";
    }

    private String buildHandoffSummary(ConversationSession session, String reason) {
        StringBuilder summary = new StringBuilder("Customer requested human assistance. Reason: ")
                .append(reason == null || reason.isBlank() ? "not specified" : reason);
        if (!session.getRecentActions().isEmpty()) {
            summary.append(". Recent activity: ");
            session.getRecentActions().forEach(a -> summary.append(a.type()).append(" ").append(a.target()).append("; "));
        }
        return summary.toString();
    }

    private ReturnReason parseReturnReason(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("size")) return ReturnReason.WRONG_SIZE;
        if (lower.contains("defect")) return ReturnReason.DEFECTIVE;
        if (lower.contains("damag")) return ReturnReason.DAMAGED;
        if (lower.contains("describ")) return ReturnReason.NOT_AS_DESCRIBED;
        if (lower.contains("mind") || lower.contains("chang")) return ReturnReason.CHANGED_MIND;
        return ReturnReason.OTHER;
    }

    /**
     * Parses the optional model-supplied return quantity. Blank means one
     * unit; a non-positive or non-numeric value returns {@code -1} so the
     * caller asks again instead of authorizing a nonsense quantity.
     */
    private static int parseReturnQuantity(String quantityText) {
        if (quantityText == null || quantityText.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(quantityText.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ClaimReason parseClaimReason(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("damag")) return ClaimReason.DAMAGED;
        if (lower.contains("defect")) return ClaimReason.DEFECTIVE;
        if (lower.contains("wrong")) return ClaimReason.WRONG_ITEM;
        if (lower.contains("missing")) return ClaimReason.MISSING_ITEM;
        return ClaimReason.OTHER;
    }

    /**
     * Gap-fix (ConversationFocus): if the model's item text fails to resolve or is ambiguous, but
     * a specific item was already pinned earlier in the conversation for this EXACT order, use it
     * directly instead of asking again - this is what makes "yes, I want to return it" reliable
     * even on a multi-item order, not just the single-item-auto-resolve case.
     */
    private OrderItem resolveItemWithFocusFallback(ConversationSession session, VerifiedOrderRef ref, String itemReference) {
        try {
            return ownedOrderItemResolver.resolve(ref, itemReference);
        } catch (ResourceNotFoundForAccountException | AmbiguousItemException e) {
            Optional<OrderItem> focusItem = session.getFocus()
                    .filter(f -> ref.orderNumber().equals(f.orderNumber()))
                    .map(ConversationFocus::itemSku)
                    .filter(sku -> sku != null)
                    .flatMap(sku -> tryResolveBySku(ref, sku));
            if (focusItem.isPresent()) {
                return focusItem.get();
            }
            throw e;
        }
    }

    private Optional<OrderItem> tryResolveBySku(VerifiedOrderRef ref, String sku) {
        try {
            return Optional.of(ownedOrderItemResolver.resolveBySku(ref, sku));
        } catch (ResourceNotFoundForAccountException e) {
            return Optional.empty();
        }
    }
}
