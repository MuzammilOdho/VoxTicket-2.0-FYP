package com.voxticket.conversation;

import com.voxticket.agent.SupportAgent;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ConfirmationClassifier;
import com.voxticket.procedure.ConfirmationDecision;
import com.voxticket.procedure.ProcedureCoordinator;
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

    private final SessionStore sessionStore;
    private final IdentityService identityService;
    private final SupportAgent supportAgent;
    private final InputNormalizer inputNormalizer;
    private final PromptGuard promptGuard;
    private final ConfirmationClassifier confirmationClassifier;
    private final OtpInputClassifier otpInputClassifier;
    private final ProcedureCoordinator procedureCoordinator;
    private final TurnMetrics turnMetrics;

    public ConversationRuntime(
            SessionStore sessionStore,
            IdentityService identityService,
            SupportAgent supportAgent,
            InputNormalizer inputNormalizer,
            PromptGuard promptGuard,
            ConfirmationClassifier confirmationClassifier,
            OtpInputClassifier otpInputClassifier,
            ProcedureCoordinator procedureCoordinator,
            TurnMetrics turnMetrics) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
        this.supportAgent = supportAgent;
        this.inputNormalizer = inputNormalizer;
        this.promptGuard = promptGuard;
        this.confirmationClassifier = confirmationClassifier;
        this.otpInputClassifier = otpInputClassifier;
        this.procedureCoordinator = procedureCoordinator;
        this.turnMetrics = turnMetrics;
    }

    public AssistantTurn processTurn(UserTurn turn) {
        long startNanos = System.nanoTime();
        return sessionStore.withSession(turn.sessionId(), turn.channel(), session -> {
            if (StringUtils.hasText(turn.callerPhone())) {
                CustomerIdentity resolved = identityService.resolveByPhone(turn.callerPhone());
                session.applyResolvedIdentity(resolved);
            }

            log.info("event=turn_start sessionId={} channel={} turnNumber={} identityAssurance={}",
                    session.getSessionId(), session.getChannel(), session.getTurnCount() + 1, session.getCustomerIdentity().assuranceLevel());

            NormalizationResult normalization = inputNormalizer.normalize(turn.text());
            if (!normalization.accepted()) {
                log.info("event=input_rejected sessionId={} reason=INPUT_TOO_LONG length={}", session.getSessionId(), normalization.rejectedLength());
                int turnNumber = session.recordUserMessage("[message rejected - too long: " + normalization.rejectedLength() + " characters]");
                session.recordAssistantMessage(TOO_LONG_MESSAGE);
                completeTurn(session, turnNumber, startNanos, "input_too_long");
                return new AssistantTurn(TOO_LONG_MESSAGE, false, false, stateView(session, turnNumber), Map.of("rejectionReason", "INPUT_TOO_LONG"));
            }

            String normalizedText = normalization.text();
            PromptGuardVerdict verdict = promptGuard.evaluate(normalizedText);
            if (verdict.suspicious()) {
                log.warn("event=input_blocked sessionId={} category={} inputLength={} inputHash={}",
                        session.getSessionId(), verdict.category(), normalizedText.length(), SafeLogging.hash(normalizedText));
                int turnNumber = session.recordUserMessage(REDACTED_FLAGGED_PLACEHOLDER);
                session.recordAssistantMessage(SAFE_DEFLECTION_MESSAGE);
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
                OtpInputResult input = otpInputClassifier.classify(normalizedText);
                var outcome = switch (input.type()) {
                    case CODE -> procedureCoordinator.submitVerificationCode(session, input.code());
                    case RESEND_REQUESTED -> procedureCoordinator.resendVerificationCode(session);
                    case OTHER -> null;
                };
                if (outcome != null) {
                    responseText = outcome.message();
                    turnMetadata = outcome.metadata();
                    outcomeLabel = "verification";
                } else {
                    responseText = supportAgent.respond(session, normalizedText);
                    outcomeLabel = "verification_unclear";
                }
            } else if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_CONFIRMATION) {
                turnNumber = session.recordUserMessage(normalizedText);
                ConfirmationDecision decision = confirmationClassifier.classify(normalizedText);
                responseText = switch (decision) {
                    case YES -> confirmWithSafeFallback(session);
                    case NO -> procedureCoordinator.declineActive(session).message();
                    case UNCLEAR -> supportAgent.respond(session, normalizedText);
                };
                outcomeLabel = "confirmation_" + decision.name().toLowerCase();
            } else {
                turnNumber = session.recordUserMessage(normalizedText);
                responseText = supportAgent.respond(session, normalizedText);
                outcomeLabel = "normal";
            }
            session.recordAssistantMessage(responseText);

            boolean stillWaiting = session.getActiveProcedure()
                    .map(p -> p.getStatus() == ProcedureStatus.AWAITING_CONFIRMATION || p.getStatus() == ProcedureStatus.AWAITING_VERIFICATION)
                    .orElse(false);
            completeTurn(session, turnNumber, startNanos, outcomeLabel);
            return new AssistantTurn(responseText, false, stillWaiting, stateView(session, turnNumber), turnMetadata);
        });
    }

    private String confirmWithSafeFallback(ConversationSession session) {
        try {
            return procedureCoordinator.confirmActive(session).message();
        } catch (Exception e) {
            log.error("event=procedure_confirmation_failed sessionId={} errorType={}", session.getSessionId(), e.getClass().getSimpleName(), e);
            return PROCEDURE_FAILURE_MESSAGE;
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