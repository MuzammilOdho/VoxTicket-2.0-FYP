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
import org.springframework.transaction.annotation.Transactional;

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
            return recordOutcome(session, ProcedureType.CANCELLATION,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos);
        }
        session.recordFocusOrder(ref.orderNumber());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())
                .orElseThrow(() -> new IllegalStateException("Order has no payment record: " + ref.orderNumber()));
        CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, payment);
        if (!eligibility.eligible()) {
            return recordOutcome(session, ProcedureType.CANCELLATION,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This order is not eligible for cancellation (" + eligibility.denialReason() + ")."),
                    ref.orderNumber(), startNanos);
        }
        String description = "cancel order " + ref.orderNumber() + "." + describePaymentConsequence(eligibility.paymentConsequence());
        return beginProcedure(session, ProcedureType.CANCELLATION, ref, Map.of(), true, description, startNanos);
    }

    public ProcedureOutcome startReturn(ConversationSession session, String orderReference, String itemReference, String reasonText) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos);
        }
        session.recordFocusOrder(ref.orderNumber());
        OrderItem item;
        try {
            item = resolveItemWithFocusFallback(session, ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            return recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"),
                    ref.orderNumber(), startNanos);
        } catch (AmbiguousItemException e) {
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"),
                    ref.orderNumber(), startNanos);
        }
        session.recordFocusItem(item.getSku(), item.getProductName());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        ReturnEligibility eligibility = returnPolicyService.evaluate(order, item);
        if (!eligibility.eligible()) {
            return recordOutcome(session, ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This item is not eligible for return (" + eligibility.denialReason() + ")."),
                    ref.orderNumber(), startNanos);
        }
        if (reasonText == null || reasonText.isBlank()) {
            return recordOutcome(session, ProcedureType.RETURN, ProcedureOutcome.error("REASON_REQUIRED",
                            "Could you tell me why you'd like to return the " + item.getProductName() + " - for example wrong size, damaged, or you changed your mind?"),
                    ref.orderNumber(), startNanos);
        }
        ReturnReason reason = parseReturnReason(reasonText);
        Map<String, String> data = Map.of("itemReference", item.getSku(), "reason", reason.name());
        String description = "start a return for " + item.getProductName() + " from order " + ref.orderNumber();
        return beginProcedure(session, ProcedureType.RETURN, ref, data, true, description, startNanos);
    }

    public ProcedureOutcome startClaim(ConversationSession session, String orderReference, String itemReference, String problemText) {
        long startNanos = System.nanoTime();
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."), orderReference, startNanos);
        }
        session.recordFocusOrder(ref.orderNumber());
        OrderItem item;
        try {
            item = resolveItemWithFocusFallback(session, ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            return recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"),
                    ref.orderNumber(), startNanos);
        } catch (AmbiguousItemException e) {
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"),
                    ref.orderNumber(), startNanos);
        }
        session.recordFocusItem(item.getSku(), item.getProductName());
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return recordOutcome(session, ProcedureType.CLAIM,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "Cannot file a claim against a cancelled order."), ref.orderNumber(), startNanos);
        }
        if (problemText == null || problemText.isBlank()) {
            return recordOutcome(session, ProcedureType.CLAIM, ProcedureOutcome.error("PROBLEM_REQUIRED",
                            "Could you tell me what happened with the " + item.getProductName() + " - for example was it damaged, defective, the wrong item, or missing?"),
                    ref.orderNumber(), startNanos);
        }
        ClaimReason reason = parseClaimReason(problemText);
        Map<String, String> data = Map.of("itemReference", item.getSku(), "reason", reason.name(), "description", problemText);
        String description = "file a claim for " + item.getProductName() + " on order " + ref.orderNumber() + " (" + reason.name().toLowerCase(Locale.ROOT) + ")";
        return beginProcedure(session, ProcedureType.CLAIM, ref, data, false, description, startNanos);
    }

    @Transactional
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
            return ProcedureOutcome.ok("ALREADY_ESCALATED", existingTicket != null
                    ? "You're already connected to our support team on ticket " + existingTicket + " - they have the details already."
                    : "You're already connected to our support team for this conversation.");
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
        return ProcedureOutcome.ok("ESCALATED", "I've let our support team know - reference " + ticket.getTicketNumber()
                + ". They'll have the details of what we've discussed so far.");
    }

    // ---- Verification (called ONLY from ConversationRuntime, never from a tool) ----

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
            return recordOutcome(session, procedure.getType(), ProcedureOutcome.error("VERIFICATION_FAILED", result.message()), orderReference, startNanos);
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
                ? ProcedureOutcome.ok("VERIFICATION_REQUIRED", outcome.message(), outcome.metadata())
                : ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", outcome.message());
    }

    // ---- Advancing a pending confirmation (CLAIM only - called ONLY from ConversationRuntime, never from a tool) ----

    @Transactional
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
        return recordOutcome(session, procedure.getType(), ProcedureOutcome.ok("DECLINED", "No problem, I won't go ahead with that."), orderReference, startNanos);
    }

    // ---- internal ----

    /**
     * Phase 10 gap-fix: every procedure outcome now records the attempted order reference and
     * elapsed duration alongside the type/code that was already there - this is what makes a
     * failed return/claim explainable from the Conversation Inspector instead of just "it failed."
     */
    private ProcedureOutcome recordOutcome(ConversationSession session, ProcedureType type, ProcedureOutcome outcome, String orderReference, long startNanos) {
        turnMetrics.recordProcedureOutcome(type.name(), outcome.code(), outcome.success());
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

    private ConversationEventType classifyProcedureEvent(ProcedureOutcome outcome) {
        if (!outcome.success()) {
            return ConversationEventType.PROCEDURE_FAILED;
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
            boolean requiresOtp, String actionDescription, long startNanos) {

        if (!session.getCustomerIdentity().isAtLeast(IdentityAssurance.PHONE_MATCHED)) {
            return recordOutcome(session, type,
                    ProcedureOutcome.error("IDENTITY_NOT_VERIFIED", "I'll need to verify who I'm speaking with before I can do that."), target.orderNumber(), startNanos);
        }

        ProcedureState procedure = new ProcedureState(type, target, data, requiresOtp ? IdentityAssurance.OTP_VERIFIED : IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription(actionDescription);
        ProcedureSlotResult slotResult = session.beginProcedure(procedure);
        if (slotResult == ProcedureSlotResult.BOTH_SLOTS_OCCUPIED) {
            String activeDesc = session.getActiveProcedure().map(this::describe).orElse("one request");
            String pausedDesc = session.getPausedProcedure().map(this::describe).orElse("another request");
            return recordOutcome(session, type, ProcedureOutcome.error("TOO_MANY_ACTIVE_PROCEDURES",
                            "There's already a " + activeDesc + " and a " + pausedDesc + " in progress. Which one would you like to continue first?"),
                    target.orderNumber(), startNanos);
        }

        if (requiresOtp) {
            procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
            VerificationOutcome verificationOutcome = verificationService.issueChallenge(session, purposeFor(type), procedure.getProcedureId(), target.orderNumber());
            if (!verificationOutcome.success()) {
                procedure.setStatus(ProcedureStatus.FAILED);
                session.clearActiveProcedure();
                return recordOutcome(session, type, ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", verificationOutcome.message()), target.orderNumber(), startNanos);
            }
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.OTP_ISSUED, "type=" + type + " orderReference=" + target.orderNumber());
            String pausedNote = slotResult == ProcedureSlotResult.STARTED_AND_PAUSED_PREVIOUS
                    ? " I've paused what we were doing before - we'll come back to it after this."
                    : "";
            return recordOutcome(session, type, ProcedureOutcome.ok("VERIFICATION_REQUIRED", verificationOutcome.message() + pausedNote), target.orderNumber(), startNanos);
        }

        return recordOutcome(session, type, moveToConfirmation(procedure), target.orderNumber(), startNanos);
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
        return ProcedureOutcome.ok("CANCELLED", "Order " + result.orderNumber() + " has been cancelled." + describePaymentConsequence(result.paymentConsequence()));
    }

    private ProcedureOutcome executeReturn(ConversationSession session, ProcedureState procedure) {
        Map<String, String> data = procedure.getCollectedData();
        ReturnReason reason = ReturnReason.valueOf(data.get("reason"));
        var returnRequest = returnService.requestReturn(procedure.getVerifiedTarget(), data.get("itemReference"), 1, reason);
        session.recordAction(new RecentAction(
                RecentActionType.RETURN_REQUESTED, procedure.getVerifiedTarget().orderNumber(), returnRequest.getStatus().name(),
                null, returnRequest.getReturnNumber(), Instant.now()));
        return ProcedureOutcome.ok("RETURN_STARTED", "Return " + returnRequest.getReturnNumber() + " has been started for order "
                + procedure.getVerifiedTarget().orderNumber() + ". I'll let you know what to do with the item next.");
    }

    private ProcedureOutcome executeClaim(ConversationSession session, ProcedureState procedure) {
        Map<String, String> data = procedure.getCollectedData();
        ClaimReason reason = ClaimReason.valueOf(data.get("reason"));
        var claim = claimService.fileClaim(procedure.getVerifiedTarget(), data.get("itemReference"), reason, ClaimResolution.MANUAL_REVIEW, data.get("description"));
        session.recordAction(new RecentAction(
                RecentActionType.CLAIM_FILED, procedure.getVerifiedTarget().orderNumber(), claim.getStatus().name(), null, claim.getClaimNumber(), Instant.now()));
        return ProcedureOutcome.ok("CLAIM_FILED", "I've filed claim " + claim.getClaimNumber() + " for order "
                + procedure.getVerifiedTarget().orderNumber() + " and opened a support ticket - our team will review it.");
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