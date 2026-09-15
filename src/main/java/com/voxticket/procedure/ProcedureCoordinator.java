package com.voxticket.procedure;

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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §12/§13/§19/§20/§21 + Core Improvements #4/#5.
 *
 * <p>CANCELLATION and RETURN always require a fresh, action-bound OTP,
 * regardless of the session's current identity assurance - a session that
 * already carries OTP_VERIFIED from a DIFFERENT, earlier action never
 * exempts a new one. OTP success itself is the authorization for that
 * specific action - there is no separate post-OTP confirmation step, and
 * OTP verification never upgrades session-wide identity assurance. CLAIM
 * never uses OTP (PHONE_MATCHED only) and keeps its existing explicit
 * yes/no confirmation flow via confirmActive.
 */
@Service
public class ProcedureCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcedureCoordinator.class);
    private static final Duration PENDING_ACTION_TTL = Duration.ofMinutes(5);

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
            TurnMetrics turnMetrics) {
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
    }

    // ---- Starting procedures (called from ProcedureRequestTools, i.e. by the model) ----

    public ProcedureOutcome startCancellation(ConversationSession session, String orderReference) {
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return recordOutcome(ProcedureType.CANCELLATION, ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."));
        }
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())
                .orElseThrow(() -> new IllegalStateException("Order has no payment record: " + ref.orderNumber()));
        CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, payment);
        if (!eligibility.eligible()) {
            return recordOutcome(ProcedureType.CANCELLATION,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This order is not eligible for cancellation (" + eligibility.denialReason() + ")."));
        }
        String description = "cancel order " + ref.orderNumber() + "." + describePaymentConsequence(eligibility.paymentConsequence());
        return beginProcedure(session, ProcedureType.CANCELLATION, ref, Map.of(), true, description);
    }

    public ProcedureOutcome startReturn(ConversationSession session, String orderReference, String itemReference, String reasonText) {
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return recordOutcome(ProcedureType.RETURN, ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."));
        }
        OrderItem item;
        try {
            item = ownedOrderItemResolver.resolve(ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            return recordOutcome(ProcedureType.RETURN, ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"));
        } catch (AmbiguousItemException e) {
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return recordOutcome(ProcedureType.RETURN, ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"));
        }
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        ReturnEligibility eligibility = returnPolicyService.evaluate(order, item);
        if (!eligibility.eligible()) {
            return recordOutcome(ProcedureType.RETURN,
                    ProcedureOutcome.error("NOT_ELIGIBLE", "This item is not eligible for return (" + eligibility.denialReason() + ")."));
        }
        ReturnReason reason = parseReturnReason(reasonText);
        Map<String, String> data = Map.of("itemReference", item.getSku(), "reason", reason.name());
        String description = "start a return for " + item.getProductName() + " from order " + ref.orderNumber();
        return beginProcedure(session, ProcedureType.RETURN, ref, data, true, description);
    }

    public ProcedureOutcome startClaim(ConversationSession session, String orderReference, String itemReference, String problemText) {
        VerifiedOrderRef ref = resolveOrder(session, orderReference);
        if (ref == null) {
            return recordOutcome(ProcedureType.CLAIM, ProcedureOutcome.error("NOT_FOUND_FOR_ACCOUNT", "That order doesn't match any of the customer's own orders."));
        }
        OrderItem item;
        try {
            item = ownedOrderItemResolver.resolve(ref, itemReference);
        } catch (ResourceNotFoundForAccountException e) {
            return recordOutcome(ProcedureType.CLAIM, ProcedureOutcome.error("ITEM_REQUIRED", "I couldn't match that to an item on this order - could you describe which item you mean?"));
        } catch (AmbiguousItemException e) {
            String candidates = e.getCandidates().stream().map(OrderItem::getProductName).collect(Collectors.joining(", "));
            return recordOutcome(ProcedureType.CLAIM, ProcedureOutcome.error("ITEM_REQUIRED", "This order has a few items that could match: " + candidates + ". Which one did you mean?"));
        }
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return recordOutcome(ProcedureType.CLAIM, ProcedureOutcome.error("NOT_ELIGIBLE", "Cannot file a claim against a cancelled order."));
        }
        ClaimReason reason = parseClaimReason(problemText);
        Map<String, String> data = Map.of("itemReference", item.getSku(), "reason", reason.name(), "description", nullToEmpty(problemText));
        String description = "file a claim for " + item.getProductName() + " on order " + ref.orderNumber() + " (" + reason.name().toLowerCase(Locale.ROOT) + ")";
        return beginProcedure(session, ProcedureType.CLAIM, ref, data, false, description);
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
        return ProcedureOutcome.ok("ESCALATED", "I've let our support team know - reference " + ticket.getTicketNumber()
                + ". They'll have the details of what we've discussed so far.");
    }

    // ---- Verification (called ONLY from ConversationRuntime, never from a tool) ----

    /**
     * Core Improvement #4: OTP success directly authorizes AND executes this exact action - no
     * separate post-OTP confirmation step. Deliberately NOT @Transactional at this level:
     * verificationService.verify(...) commits its own transaction independently (consuming the
     * code) BEFORE execution is attempted. If it were wrapped in one shared transaction with the
     * business mutation and the mutation later failed, the rollback could undo the "consumed"
     * flag too, leaving an already-used code replayable.
     */
    public ProcedureOutcome submitVerificationCode(ConversationSession session, String code) {
        ProcedureState procedure = session.getActiveProcedure().filter(p -> p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION).orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_VERIFICATION", "There's no verification code pending right now.");
        }
        VerificationResult result = verificationService.verify(session, code, procedure.getProcedureId(), procedure.getVerifiedTarget().orderNumber());
        if (!result.verified()) {
            return recordOutcome(procedure.getType(), ProcedureOutcome.error("VERIFICATION_FAILED", result.message()));
        }

        ProcedureOutcome outcome;
        try {
            // "recheck ownership, current DB state, and eligibility before executing" is already
            // covered here: CancellationService/ReturnService re-check eligibility against fresh
            // DB state on every call (proven below), and ownership can't have changed since
            // orders are never reassigned between customers in this system.
            outcome = execute(session, procedure);
        } catch (RuntimeException e) {
            log.error("event=procedure_execution_failed type={} orderNumber={} errorType={}",
                    procedure.getType(), procedure.getVerifiedTarget().orderNumber(), e.getClass().getSimpleName(), e);
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            turnMetrics.recordProcedureOutcome(procedure.getType().name(), "EXECUTION_FAILED", false);
            throw e;
        }
        procedure.setStatus(ProcedureStatus.EXECUTED);
        session.clearActiveProcedure();
        return recordOutcome(procedure.getType(), outcome);
    }

    public ProcedureOutcome resendVerificationCode(ConversationSession session) {
        ProcedureState procedure = session.getActiveProcedure().filter(p -> p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION).orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_VERIFICATION", "There's no verification in progress right now.");
        }
        VerificationOutcome outcome = verificationService.issueChallenge(
                session, purposeFor(procedure.getType()), procedure.getProcedureId(), procedure.getVerifiedTarget().orderNumber());
        return outcome.success()
                ? ProcedureOutcome.ok("VERIFICATION_REQUIRED", outcome.message(), outcome.metadata())
                : ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", outcome.message());
    }

    // ---- Advancing a pending confirmation (CLAIM only now - called ONLY from ConversationRuntime, never from a tool) ----

    @Transactional
    public ProcedureOutcome confirmActive(ConversationSession session) {
        ProcedureState procedure = session.getActiveProcedure().orElse(null);
        if (procedure == null || procedure.getStatus() != ProcedureStatus.AWAITING_CONFIRMATION) {
            return ProcedureOutcome.error("NO_PENDING_CONFIRMATION", "There's nothing waiting for confirmation right now.");
        }
        PendingAction pendingAction = procedure.getPendingAction();
        if (pendingAction == null || Instant.now().isAfter(pendingAction.expiresAt())) {
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            return recordOutcome(procedure.getType(), ProcedureOutcome.error("EXPIRED", "That request has expired - let's start again if you'd still like to go ahead."));
        }

        PolicyDecision recheck = actionPolicyService.evaluate(new ActionRequest(procedure.getType(), procedure.getRequiredAssurance(), true), session);
        if (recheck.outcome() != PolicyOutcome.ALLOW) {
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            return recordOutcome(procedure.getType(),
                    ProcedureOutcome.error("IDENTITY_NOT_VERIFIED", "This still needs identity verification we can't complete."));
        }

        ProcedureOutcome outcome;
        try {
            outcome = execute(session, procedure);
        } catch (RuntimeException e) {
            log.error("event=procedure_execution_failed type={} orderNumber={} errorType={}",
                    procedure.getType(), procedure.getVerifiedTarget().orderNumber(), e.getClass().getSimpleName(), e);
            procedure.setStatus(ProcedureStatus.FAILED);
            session.clearActiveProcedure();
            turnMetrics.recordProcedureOutcome(procedure.getType().name(), "EXECUTION_FAILED", false);
            throw e;
        }
        procedure.setStatus(ProcedureStatus.EXECUTED);
        session.clearActiveProcedure();
        return recordOutcome(procedure.getType(), outcome);
    }

    public ProcedureOutcome declineActive(ConversationSession session) {
        ProcedureState procedure = session.getActiveProcedure().orElse(null);
        if (procedure == null) {
            return ProcedureOutcome.error("NO_PENDING_CONFIRMATION", "There's nothing waiting for confirmation right now.");
        }
        procedure.setStatus(ProcedureStatus.CANCELLED);
        session.clearActiveProcedure();
        return recordOutcome(procedure.getType(), ProcedureOutcome.ok("DECLINED", "No problem, I won't go ahead with that."));
    }

    // ---- internal ----

    private ProcedureOutcome recordOutcome(ProcedureType type, ProcedureOutcome outcome) {
        turnMetrics.recordProcedureOutcome(type.name(), outcome.code(), outcome.success());
        return outcome;
    }

    private VerifiedOrderRef resolveOrder(ConversationSession session, String orderReference) {
        try {
            return ownedOrderResolver.resolve(session.getCustomerIdentity(), orderReference);
        } catch (ResourceNotFoundForAccountException e) {
            return null;
        }
    }

    /**
     * Core Improvement #4/#5: TYPE dictates the path, never session assurance. CANCELLATION/
     * RETURN always require a fresh, action-bound OTP - there is no check of whether the session
     * already carries OTP_VERIFIED from an earlier, unrelated action, because that earlier OTP
     * must never authorize a different mutation. CLAIM only needs PHONE_MATCHED and keeps the
     * existing explicit confirmation step.
     */
    private ProcedureOutcome beginProcedure(
            ConversationSession session, ProcedureType type, VerifiedOrderRef target, Map<String, String> data,
            boolean requiresOtp, String actionDescription) {

        if (!session.getCustomerIdentity().isAtLeast(IdentityAssurance.PHONE_MATCHED)) {
            return recordOutcome(type, ProcedureOutcome.error("IDENTITY_NOT_VERIFIED", "I'll need to verify who I'm speaking with before I can do that."));
        }

        ProcedureState procedure = new ProcedureState(type, target, data, requiresOtp ? IdentityAssurance.OTP_VERIFIED : IdentityAssurance.PHONE_MATCHED);
        procedure.setPendingDescription(actionDescription);
        ProcedureSlotResult slotResult = session.beginProcedure(procedure);
        if (slotResult == ProcedureSlotResult.BOTH_SLOTS_OCCUPIED) {
            String activeDesc = session.getActiveProcedure().map(this::describe).orElse("one request");
            String pausedDesc = session.getPausedProcedure().map(this::describe).orElse("another request");
            return recordOutcome(type, ProcedureOutcome.error("TOO_MANY_ACTIVE_PROCEDURES",
                    "There's already a " + activeDesc + " and a " + pausedDesc + " in progress. Which one would you like to continue first?"));
        }

        if (requiresOtp) {
            procedure.setStatus(ProcedureStatus.AWAITING_VERIFICATION);
            VerificationOutcome verificationOutcome = verificationService.issueChallenge(session, purposeFor(type), procedure.getProcedureId(), target.orderNumber());
            if (!verificationOutcome.success()) {
                session.clearActiveProcedure();
                return recordOutcome(type, ProcedureOutcome.error("VERIFICATION_RATE_LIMITED", verificationOutcome.message()));
            }
            String pausedNote = slotResult == ProcedureSlotResult.STARTED_AND_PAUSED_PREVIOUS
                    ? " I've paused what we were doing before - we'll come back to it after this."
                    : "";
            return recordOutcome(type, ProcedureOutcome.ok("VERIFICATION_REQUIRED", verificationOutcome.message() + pausedNote));
        }

        return recordOutcome(type, moveToConfirmation(procedure));
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
        session.recordAction(new RecentAction(RecentActionType.ORDER_CANCELLED, result.orderNumber(), "CANCELLED", null, result.orderNumber(), Instant.now()));
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
        String lower = nullToEmpty(text).toLowerCase(Locale.ROOT);
        if (lower.contains("size")) return ReturnReason.WRONG_SIZE;
        if (lower.contains("defect")) return ReturnReason.DEFECTIVE;
        if (lower.contains("damag")) return ReturnReason.DAMAGED;
        if (lower.contains("describ")) return ReturnReason.NOT_AS_DESCRIBED;
        if (lower.contains("mind") || lower.contains("chang")) return ReturnReason.CHANGED_MIND;
        return ReturnReason.OTHER;
    }

    private ClaimReason parseClaimReason(String text) {
        String lower = nullToEmpty(text).toLowerCase(Locale.ROOT);
        if (lower.contains("damag")) return ClaimReason.DAMAGED;
        if (lower.contains("defect")) return ClaimReason.DEFECTIVE;
        if (lower.contains("wrong")) return ClaimReason.WRONG_ITEM;
        if (lower.contains("missing")) return ClaimReason.MISSING_ITEM;
        return ClaimReason.OTHER;
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}