package com.voxticket.conversation;

import com.voxticket.agent.SupportAgent;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.procedure.ConfirmationClassifier;
import com.voxticket.procedure.ConfirmationDecision;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureOutcome;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.NormalizationResult;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.safety.SafeLogging;
import com.voxticket.verification.OtpInputClassifier;
import com.voxticket.verification.OtpInputResult;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConversationRuntime {

    private static final Logger log = LoggerFactory.getLogger(ConversationRuntime.class);

    private static final String SAFE_DEFLECTION_MESSAGE =
            "I'm not able to help with that. I can help with questions about your orders, shipments, payments, refunds, returns, or support tickets.";
    private static final String TOO_LONG_MESSAGE = "That message was too long for me to process - could you break it into shorter messages?";
    private static final String REDACTED_FLAGGED_PLACEHOLDER = "[message withheld - flagged by security filter]";
    private static final String PROCEDURE_FAILURE_MESSAGE = "Something went wrong while processing that - please try again, or ask for a human agent.";

    /** Phase 10 gap-fix (proposal #3): a small, evidence-grounded set of phrases observed fabricating a failure with no tool ever attempted. Diagnostic only - never changes behavior. */
    private static final List<String> FABRICATION_SIGNAL_PHRASES = List.of(
            "having trouble", "technical issue", "technical problem", "system issue", "system problem", "couldn't start", "couldn't process");

    private final SessionStore sessionStore;
    private final IdentityService identityService;
    private final SupportAgent supportAgent;
    private final InputNormalizer inputNormalizer;
    private final PromptGuard promptGuard;
    private final ConfirmationClassifier confirmationClassifier;
    private final OtpInputClassifier otpInputClassifier;
    private final ProcedureCoordinator procedureCoordinator;
    private final TurnMetrics turnMetrics;
    private final ConversationAuditService auditService;

    public ConversationRuntime(
            SessionStore sessionStore,
            IdentityService identityService,
            SupportAgent supportAgent,
            InputNormalizer inputNormalizer,
            PromptGuard promptGuard,
            ConfirmationClassifier confirmationClassifier,
            OtpInputClassifier otpInputClassifier,
            ProcedureCoordinator procedureCoordinator,
            TurnMetrics turnMetrics,
            ConversationAuditService auditService) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
        this.supportAgent = supportAgent;
        this.inputNormalizer = inputNormalizer;
        this.promptGuard = promptGuard;
        this.confirmationClassifier = confirmationClassifier;
        this.otpInputClassifier = otpInputClassifier;
        this.procedureCoordinator = procedureCoordinator;
        this.turnMetrics = turnMetrics;
        this.auditService = auditService;
    }

    public AssistantTurn processTurn(UserTurn turn) {
        long startNanos = System.nanoTime();
        return sessionStore.withSession(turn.sessionId(), turn.channel(), session -> {
            if (StringUtils.hasText(turn.callerPhone())) {
                CustomerIdentity resolved = identityService.resolveByPhone(turn.callerPhone());
                session.applyResolvedIdentity(resolved);
            }
            auditService.recordSessionTouch(session);

            log.info("event=turn_start sessionId={} channel={} turnNumber={} identityAssurance={}",
                    session.getSessionId(), session.getChannel(), session.getTurnCount() + 1, session.getCustomerIdentity().assuranceLevel());

            NormalizationResult normalization = inputNormalizer.normalize(turn.text());
            if (!normalization.accepted()) {
                log.info("event=input_rejected sessionId={} reason=INPUT_TOO_LONG length={}", session.getSessionId(), normalization.rejectedLength());
                int turnNumber = session.recordUserMessage("[message rejected - too long: " + normalization.rejectedLength() + " characters]");
                auditService.recordMessage(session, turnNumber, MessageRole.USER, "[message rejected - too long: " + normalization.rejectedLength() + " characters]");
                session.recordAssistantMessage(TOO_LONG_MESSAGE);
                auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, TOO_LONG_MESSAGE);
                completeTurn(session, turnNumber, startNanos, "input_too_long");
                return new AssistantTurn(TOO_LONG_MESSAGE, false, false, stateView(session, turnNumber), Map.of("rejectionReason", "INPUT_TOO_LONG"));
            }

            String normalizedText = normalization.text();
            PromptGuardVerdict verdict = promptGuard.evaluate(normalizedText);
            if (verdict.suspicious()) {
                log.warn("event=input_blocked sessionId={} category={} inputLength={} inputHash={}",
                        session.getSessionId(), verdict.category(), normalizedText.length(), SafeLogging.hash(normalizedText));
                int turnNumber = session.recordUserMessage(REDACTED_FLAGGED_PLACEHOLDER);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, REDACTED_FLAGGED_PLACEHOLDER);
                auditService.recordEvent(session, turnNumber, ConversationEventType.SAFETY_BLOCKED, "category=" + verdict.category());
                session.recordAssistantMessage(SAFE_DEFLECTION_MESSAGE);
                auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, SAFE_DEFLECTION_MESSAGE);
                completeTurn(session, turnNumber, startNanos, "blocked");
                return new AssistantTurn(SAFE_DEFLECTION_MESSAGE, false, false, stateView(session, turnNumber), Map.of());
            }

            String responseText;
            int turnNumber;
            String outcomeLabel;
            Map<String, String> turnMetadata = Map.of();
            Optional<ProcedureState> active = session.getActiveProcedure();

            if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_VERIFICATION) {
                turnNumber = session.recordUserMessage(normalizedText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                OtpInputResult input = otpInputClassifier.classify(normalizedText);
                ProcedureOutcome outcome = switch (input.type()) {
                    case CODE -> submitVerificationWithSafeFallback(session, input.code());
                    case RESEND_REQUESTED -> procedureCoordinator.resendVerificationCode(session);
                    case OTHER -> null;
                };
                if (outcome != null) {
                    responseText = outcome.message();
                    turnMetadata = outcome.metadata();
                    outcomeLabel = "verification";
                } else {
                    responseText = respondViaAgent(session, normalizedText);
                    outcomeLabel = "verification_unclear";
                }
            } else if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_CONFIRMATION) {
                turnNumber = session.recordUserMessage(normalizedText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                ConfirmationDecision decision = confirmationClassifier.classify(normalizedText);
                responseText = switch (decision) {
                    case YES -> confirmWithSafeFallback(session);
                    case NO -> procedureCoordinator.declineActive(session).message();
                    case UNCLEAR -> respondViaAgent(session, normalizedText);
                };
                outcomeLabel = "confirmation_" + decision.name().toLowerCase();
            } else {
                turnNumber = session.recordUserMessage(normalizedText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                responseText = respondViaAgent(session, normalizedText);
                outcomeLabel = "normal";
            }
            session.recordAssistantMessage(responseText);
            auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, responseText);

            boolean stillWaiting = session.getActiveProcedure()
                    .map(p -> p.getStatus() == ProcedureStatus.AWAITING_CONFIRMATION || p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION)
                    .orElse(false);
            completeTurn(session, turnNumber, startNanos, outcomeLabel);
            return new AssistantTurn(responseText, false, stillWaiting, stateView(session, turnNumber), turnMetadata);
        });
    }

    /** Wraps every SupportAgent.respond call so the fabrication check always has a clean per-turn tool-invocation signal to check against. */
    private String respondViaAgent(ConversationSession session, String normalizedText) {
        session.resetToolInvokedFlag();
        String responseText = supportAgent.respond(session, normalizedText);
        checkForSuspectedFabrication(session, responseText);
        return responseText;
    }

    private void checkForSuspectedFabrication(ConversationSession session, String responseText) {
        if (session.wasToolInvokedThisTurn() || responseText == null) {
            return;
        }
        String lower = responseText.toLowerCase(Locale.ROOT);
        if (FABRICATION_SIGNAL_PHRASES.stream().anyMatch(lower::contains)) {
            log.warn("event=suspected_fabrication sessionId={}", session.getSessionId());
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.SUSPECTED_FABRICATION, "no tool was invoked this turn");
        }
    }

    private String confirmWithSafeFallback(ConversationSession session) {
        try {
            return procedureCoordinator.confirmActive(session).message();
        } catch (Exception e) {
            log.error("event=procedure_confirmation_failed sessionId={} errorType={}", session.getSessionId(), e.getClass().getSimpleName(), e);
            return PROCEDURE_FAILURE_MESSAGE;
        }
    }

    private ProcedureOutcome submitVerificationWithSafeFallback(ConversationSession session, String code) {
        try {
            return procedureCoordinator.submitVerificationCode(session, code);
        } catch (Exception e) {
            log.error("event=procedure_verification_execution_failed sessionId={} errorType={}", session.getSessionId(), e.getClass().getSimpleName(), e);
            return ProcedureOutcome.error("EXECUTION_FAILED", PROCEDURE_FAILURE_MESSAGE);
        }
    }

    private void completeTurn(ConversationSession session, int turnNumber, long startNanos, String outcome) {
        long durationNanos = System.nanoTime() - startNanos;
        log.info("event=turn_end sessionId={} turnNumber={} messageCount={} outcome={} durationMs={}",
                session.getSessionId(), turnNumber, session.getRecentMessages().size(), outcome, durationNanos / 1_000_000);
        turnMetrics.recordTurn(Duration.ofNanos(durationNanos), session.getChannel().name(), outcome);
    }

    private ConversationStateView stateView(ConversationSession session, int turnNumber) {
        return new ConversationStateView(session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel(), turnNumber);
    }
}