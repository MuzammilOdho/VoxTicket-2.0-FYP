package com.voxticket.conversation;

import com.voxticket.agent.SupportAgent;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import com.voxticket.procedure.ConfirmationClassifier;
import com.voxticket.procedure.ConfirmationDecision;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.NormalizationResult;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.safety.SafeLogging;
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

    private final SessionStore sessionStore;
    private final IdentityService identityService;
    private final SupportAgent supportAgent;
    private final InputNormalizer inputNormalizer;
    private final PromptGuard promptGuard;
    private final ConfirmationClassifier confirmationClassifier;
    private final ProcedureCoordinator procedureCoordinator;

    public ConversationRuntime(
            SessionStore sessionStore,
            IdentityService identityService,
            SupportAgent supportAgent,
            InputNormalizer inputNormalizer,
            PromptGuard promptGuard,
            ConfirmationClassifier confirmationClassifier,
            ProcedureCoordinator procedureCoordinator) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
        this.supportAgent = supportAgent;
        this.inputNormalizer = inputNormalizer;
        this.promptGuard = promptGuard;
        this.confirmationClassifier = confirmationClassifier;
        this.procedureCoordinator = procedureCoordinator;
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
                logTurnEnd(session, turnNumber, startNanos, false);
                return new AssistantTurn(
                        TOO_LONG_MESSAGE, false, false, stateView(session, turnNumber), Map.of("rejectionReason", "INPUT_TOO_LONG"));
            }

            String normalizedText = normalization.text();
            PromptGuardVerdict verdict = promptGuard.evaluate(normalizedText);
            if (verdict.suspicious()) {
                log.warn("event=input_blocked sessionId={} category={} inputLength={} inputHash={}",
                        session.getSessionId(), verdict.category(), normalizedText.length(), SafeLogging.hash(normalizedText));
                int turnNumber = session.recordUserMessage(REDACTED_FLAGGED_PLACEHOLDER);
                session.recordAssistantMessage(SAFE_DEFLECTION_MESSAGE);
                logTurnEnd(session, turnNumber, startNanos, false);
                return new AssistantTurn(SAFE_DEFLECTION_MESSAGE, false, false, stateView(session, turnNumber), Map.of());
            }

            String responseText;
            int turnNumber;
            Optional<com.voxticket.procedure.ProcedureState> awaitingConfirmation = session.getActiveProcedure()
                    .filter(p -> p.getStatus() == ProcedureStatus.AWAITING_CONFIRMATION);
            if (awaitingConfirmation.isPresent()) {
                ConfirmationDecision decision = confirmationClassifier.classify(normalizedText);
                turnNumber = session.recordUserMessage(normalizedText);
                responseText = switch (decision) {
                    case YES -> procedureCoordinator.confirmActive(session).message();
                    case NO -> procedureCoordinator.declineActive(session).message();
                    case UNCLEAR -> supportAgent.respond(session, normalizedText);
                };
            } else {
                turnNumber = session.recordUserMessage(normalizedText);
                responseText = supportAgent.respond(session, normalizedText);
            }
            session.recordAssistantMessage(responseText);

            boolean stillAwaitingConfirmation = session.getActiveProcedure()
                    .map(p -> p.getStatus() == ProcedureStatus.AWAITING_CONFIRMATION)
                    .orElse(false);
            logTurnEnd(session, turnNumber, startNanos, false);
            return new AssistantTurn(responseText, false, stillAwaitingConfirmation, stateView(session, turnNumber), Map.of());
        });
    }

    private void logTurnEnd(ConversationSession session, int turnNumber, long startNanos, boolean withheld) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("event=turn_end sessionId={} turnNumber={} messageCount={} withheld={} durationMs={}",
                session.getSessionId(), turnNumber, session.getRecentMessages().size(), withheld, durationMs);
    }

    private ConversationStateView stateView(ConversationSession session, int turnNumber) {
        return new ConversationStateView(session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel(), turnNumber);
    }
}