package com.voxticket.conversation;

import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.procedure.ConfirmationDecision;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureOutcome;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.NormalizationResult;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.safety.SafeLogging;
import com.voxticket.verification.SensitiveTurn;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ExplicitConfirmationParser confirmationParser;
    private final SensitiveTurnParser sensitiveTurnParser;
    private final ProcedureCoordinator procedureCoordinator;
    private final TurnMetrics turnMetrics;
    private final ConversationAuditService auditService;
    private final ConversationLanguageResolver languageResolver;
    private final DirectProcedureResponseRenderer directRenderer;

    public ConversationRuntime(
            SessionStore sessionStore,
            IdentityService identityService,
            SupportAgent supportAgent,
            InputNormalizer inputNormalizer,
            PromptGuard promptGuard,
            ExplicitConfirmationParser confirmationParser,
            SensitiveTurnParser sensitiveTurnParser,
            ProcedureCoordinator procedureCoordinator,
            TurnMetrics turnMetrics,
            ConversationAuditService auditService,
            ConversationLanguageResolver languageResolver,
            DirectProcedureResponseRenderer directRenderer) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
        this.supportAgent = supportAgent;
        this.inputNormalizer = inputNormalizer;
        this.promptGuard = promptGuard;
        this.confirmationParser = confirmationParser;
        this.sensitiveTurnParser = sensitiveTurnParser;
        this.procedureCoordinator = procedureCoordinator;
        this.turnMetrics = turnMetrics;
        this.auditService = auditService;
        this.languageResolver = languageResolver;
        this.directRenderer = directRenderer;
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
                // Pass 2D-A: parse and redact BEFORE anything is stored. OTP
                // plaintext must never enter conversation history, audit, or
                // model input - the raw code survives only in the in-memory
                // local variable handed to the verification service.
                SensitiveTurn sensitive = sensitiveTurnParser.parse(normalizedText);
                log.info("event=sensitive_turn sessionId={} otpCandidatePresent={} multipleCandidates={} resendRequested={} residualPresent={}",
                        session.getSessionId(), sensitive.hasOtpCandidate(), sensitive.multipleCandidates(),
                        sensitive.resendRequested(), sensitive.hasResidual());
                String historyText = sensitive.redactedText();
                turnNumber = session.recordUserMessage(historyText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, historyText);

                if (sensitive.multipleCandidates()) {
                    // Never guess between distinct codes: ask for one code and submit nothing.
                    ProcedureOutcome ambiguous = ProcedureOutcome.error("OTP_AMBIGUOUS", "multiple verification codes in one message");
                    responseText = renderDirect(session, ambiguous);
                    // Pass 2C security cleanup: the AssistantTurn boundary is
                    // customer-facing, so only allowlisted safe fields cross it.
                    turnMetadata = safeDirectTurnMetadata(ambiguous);
                    outcomeLabel = "verification_ambiguous";
                } else if (sensitive.hasOtpCandidate()) {
                    // Pass 2C: the direct path never reaches the LLM. Presentation is
                    // rendered deterministically from the stable outcome code and
                    // safe metadata in the customer's language.
                    ProcedureOutcome outcome = submitVerificationWithSafeFallback(session, sensitive.otpCandidate());
                    responseText = renderDirect(session, outcome);
                    // Pass 2C security cleanup: internal DEV metadata (devOtp,
                    // hashes, salts, internal IDs) stays inside the
                    // verification/coordinator layer.
                    turnMetadata = safeDirectTurnMetadata(outcome);
                    outcomeLabel = "verification";
                    // Pass 2D-B: a terminal execution may have promoted the
                    // deferred intent; its fresh start is communicated in the
                    // same turn, deterministically rendered.
                    responseText = appendPromotion(session, responseText, outcome);
                    if (sensitive.hasResidual()) {
                        // Pass 2D-B: GUARDED while a procedure is still live
                        // (possibly the freshly promoted one); FULL only when
                        // no procedure remains. The coordinator makes a second
                        // live procedure impossible, so guarded turns no longer
                        // need to be read-only.
                        AgentResponse residual = isGuardedProcedureActive(session)
                                ? respondGuardedViaAgent(session, sensitive.residualText())
                                : respondViaAgent(session, sensitive.residualText());
                        responseText = combineResponses(responseText, residual.text());
                    }
                } else if (sensitive.resendRequested()) {
                    ProcedureOutcome outcome = procedureCoordinator.resendVerificationCode(session);
                    responseText = renderDirect(session, outcome);
                    turnMetadata = safeDirectTurnMetadata(outcome);
                    outcomeLabel = "verification";
                    if (sensitive.hasResidual()) {
                        // The guarded verification is still unresolved.
                        AgentResponse residual = respondGuardedViaAgent(session, sensitive.residualText());
                        responseText = combineResponses(responseText, residual.text());
                    }
                } else {
                    // Pass 2D-B: a guarded verification is unresolved - the
                    // model gets GUARDED tools so it can understand a second
                    // mutation request (deferred by the coordinator), a
                    // correction, or an explicit replacement. A second live
                    // procedure is impossible by coordinator construction.
                    AgentResponse agentResponse = respondGuardedViaAgent(session, normalizedText);
                    responseText = agentResponse.text();
                    outcomeLabel = agentResponse.outcome().toTurnLabel("verification_unclear");
                }
            } else if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_CONFIRMATION) {
                turnNumber = session.recordUserMessage(normalizedText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                ConfirmationDecision decision = confirmationParser.classify(normalizedText);
                AgentResponse unclearResponse = null;
                responseText = switch (decision) {
                    // Pass 2C: YES/NO advance the pending confirmation deterministically -
                    // only the customer presentation step goes through the direct renderer.
                    // Pass 2D-B: a terminal outcome may promote the deferred intent;
                    // both pieces are communicated deterministically in one turn.
                    case YES -> {
                        ProcedureOutcome confirmed = confirmWithSafeFallback(session);
                        yield appendPromotion(session, renderDirect(session, confirmed), confirmed);
                    }
                    case NO -> {
                        ProcedureOutcome declined = procedureCoordinator.declineActive(session);
                        yield appendPromotion(session, renderDirect(session, declined), declined);
                    }
                    case UNCLEAR -> {
                        // Pass 2D-B: confirmation is unresolved - answer guarded so the
                        // agent can understand a second request (deferred by the
                        // coordinator), a correction, or an explicit replacement.
                        // The coordinator makes a second live procedure impossible.
                        unclearResponse = respondGuardedViaAgent(session, normalizedText);
                        yield unclearResponse.text();
                    }
                };
                outcomeLabel = unclearResponse != null
                        ? unclearResponse.outcome().toTurnLabel("confirmation_unclear")
                        : "confirmation_" + decision.name().toLowerCase();
            } else {
                turnNumber = session.recordUserMessage(normalizedText);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                // Pass 2D-B: a queued deferred intent still needs guarding
                // even with no live procedure (e.g. after abandonment) - the
                // model gets the control tools so it can resolve the
                // contested slot explicitly instead of the coordinator
                // silently deciding the queued request's fate.
                AgentResponse agentResponse = session.getDeferredIntent().isPresent()
                        ? respondGuardedViaAgent(session, normalizedText)
                        : respondViaAgent(session, normalizedText);
                responseText = agentResponse.text();
                outcomeLabel = agentResponse.outcome().toTurnLabel("normal");
            }
            session.recordAssistantMessage(responseText);
            auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, responseText);

            ProcedureStatus activeStatus = session.getActiveProcedure().map(ProcedureState::getStatus).orElse(null);
            boolean requiresVerification = activeStatus == ProcedureStatus.AWAITING_VERIFICATION;
            boolean requiresConfirmation = activeStatus == ProcedureStatus.AWAITING_CONFIRMATION;
            completeTurn(session, turnNumber, startNanos, outcomeLabel);
            return new AssistantTurn(responseText, requiresVerification, requiresConfirmation, stateView(session, turnNumber), turnMetadata);
        });
    }


    /**
     * Pass 2C security cleanup: the direct OTP/confirmation path must never
     * carry internal DEV metadata into {@link AssistantTurn#metadata()}, which
     * is customer-facing. {@code VerificationService} intentionally exposes
     * {@code devOtp} in DEV-only internal metadata (needed by existing
     * local/integration test mechanics), so the sanitization happens here at
     * the customer-response boundary, not in the verification layer.
     *
     * <p>Explicit allowlist: only fields that are already safe and
     * customer-visible survive. Everything else - {@code devOtp}, {@code otp},
     * {@code otpHash}, {@code otpSalt}, internal IDs ({@code challengeId},
     * {@code procedureId}, {@code customerId}, {@code orderId},
     * {@code paymentId}, {@code itemId}), and any raw exception information -
     * is dropped, however it is named.
     */
    private static final Set<String> DIRECT_TURN_METADATA_ALLOWLIST = Set.of(
            "orderReference",
            "paymentConsequence",
            "returnNumber",
            "claimNumber",
            "maskedDestination",
            "verificationReason",
            "verificationIssue");

    /**
     * Copies only allowlisted entries from the outcome's internal metadata.
     * The renderer still receives the full internal {@link ProcedureOutcome}
     * (it only reads explicit safe keys and ignores {@code devOtp}); this
     * method guards the {@link AssistantTurn} boundary instead.
     */
    private Map<String, String> safeDirectTurnMetadata(ProcedureOutcome outcome) {
        Map<String, String> metadata = outcome == null ? Map.of() : outcome.metadata();
        if (metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (String key : DIRECT_TURN_METADATA_ALLOWLIST) {
            String value = metadata.get(key);
            if (value != null) {
                safe.put(key, value);
            }
        }
        return Map.copyOf(safe);
    }

    /**
     * Pass 2D-B: the single predicate for the guarded-procedure invariant. A
     * procedure is guarded while it is {@code AWAITING_VERIFICATION} or
     * {@code AWAITING_CONFIRMATION}; any turn not directly consumed as the
     * guarded authorization action must then use GUARDED (never FULL) agent
     * mode.
     *
     * <p>Checked <em>after</em> OTP execution, because execution may have
     * promoted the deferred intent into a fresh guarded procedure that must
     * keep the residual guarded.
     */
    private boolean isGuardedProcedureActive(ConversationSession session) {
        return session.getActiveProcedure()
                .map(procedure -> {
                    ProcedureStatus status = procedure.getStatus();
                    return status == ProcedureStatus.AWAITING_VERIFICATION
                            || status == ProcedureStatus.AWAITING_CONFIRMATION;
                })
                .orElse(false);
    }

    /**
     * Pass 2D-B: guarded variant used while one procedure is still live. The
     * model gets the procedure-request and safe control tools; the
     * coordinator guarantees an identical request reuses the live procedure
     * and a different request is deferred, so no second live procedure can
     * start.
     */
    private AgentResponse respondGuardedViaAgent(ConversationSession session, String text) {
        session.resetToolInvokedFlag();
        AgentResponse response = supportAgent.respondGuarded(session, text);
        checkForSuspectedFabrication(session, response);
        return response;
    }

    /**
     * Pass 2D-B: after a deterministic terminal procedure outcome, promote
     * the deferred intent (if any) and append its deterministically rendered
     * fresh start. One user turn still yields one AssistantTurn; the
     * deterministic mutation outcome is never rewritten by the model.
     * Promotion is skipped after EXECUTION_FAILED - the queued intent stays
     * queued for the model to address on a later turn.
     *
     * <p>Pass 2D-B cleanup: the coordinator contains promotion failures and
     * returns a safe PROMOTION_FAILED outcome instead of throwing, but this
     * last-resort guard guarantees the invariant even if something
     * unforeseen escapes - an already-rendered successful mutation result is
     * never replaced by a turn-level failure. The deferred intent stays
     * queued for the model to address on a later turn.
     */
    private String appendPromotion(ConversationSession session, String responseText, ProcedureOutcome outcome) {
        if (outcome == null || "EXECUTION_FAILED".equals(outcome.code())) {
            return responseText;
        }
        try {
            return procedureCoordinator.promoteDeferredIntent(session)
                    .map(promoted -> combineResponses(responseText, renderDirect(session, promoted)))
                    .orElse(responseText);
        } catch (RuntimeException e) {
            // Last-resort containment: an unexpected promotion exception
            // escapes nothing and cannot replace the already-rendered active
            // success with a global failure. The customer is told the queued
            // request could not be started (it survives in the session and
            // will be retried next turn), so the request is not silently
            // dropped while the completed mutation stays exactly as rendered.
            log.warn("event=promotion_orchestration_failed sessionId={} errorType={}",
                    session.getSessionId(), e.getClass().getSimpleName());
            return combineResponses(responseText, renderDirect(session,
                    ProcedureOutcome.error("PROMOTION_FAILED", "internal promotion exception")));
        }
    }

    /** Wraps every SupportAgent.respond call so the fabrication check always has a clean per-turn tool-invocation signal to check against. */
    private AgentResponse respondViaAgent(ConversationSession session, String normalizedText) {
        session.resetToolInvokedFlag();
        AgentResponse response = supportAgent.respond(session, normalizedText);
        checkForSuspectedFabrication(session, response);
        return response;
    }

    /**
     * Pass 2D-A: one user turn yields one AssistantTurn. The deterministic
     * procedure text is never rewritten by the model; the residual agent text
     * is appended plainly (no markdown), skipping empty fragments.
     */
    private String combineResponses(String directText, String residualText) {
        String direct = directText == null ? "" : directText.strip();
        String residual = residualText == null ? "" : residualText.strip();
        if (direct.isEmpty()) {
            return residual;
        }
        if (residual.isEmpty()) {
            return direct;
        }
        return direct + " " + residual;
    }

    private void checkForSuspectedFabrication(ConversationSession session, AgentResponse response) {
        // Fabrication detection applies ONLY to successful model responses: a provider/model
        // failure (MODEL_ERROR) or a blank fallback (BLANK_FALLBACK) is never evidence of
        // fabrication - the safe recovery text is static, never model output eligible for
        // grounding validation. Gating on the outcome (not the fallback text) keeps a real
        // provider outage from being mislabeled as suspected fabrication.
        if (response == null || response.outcome() != AgentResponse.Outcome.SUCCESS) {
            return;
        }
        if (session.wasToolInvokedThisTurn() || response.text() == null) {
            return;
        }
        String lower = response.text().toLowerCase(Locale.ROOT);
        if (FABRICATION_SIGNAL_PHRASES.stream().anyMatch(lower::contains)) {
            log.warn("event=suspected_fabrication sessionId={}", session.getSessionId());
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.SUSPECTED_FABRICATION, "no tool was invoked this turn");
        }
    }

    /**
     * Pass 2C: resolves the language from the session's recent user history
     * (which already contains the current turn's user message) and renders
     * the direct-path outcome deterministically - no LLM call.
     */
    private String renderDirect(ConversationSession session, ProcedureOutcome outcome) {
        return directRenderer.render(languageResolver.resolve(session), outcome);
    }

    private ProcedureOutcome confirmWithSafeFallback(ConversationSession session) {
        try {
            return procedureCoordinator.confirmActive(session);
        } catch (Exception e) {
            log.error("event=procedure_confirmation_failed sessionId={} errorType={}", session.getSessionId(), e.getClass().getSimpleName(), e);
            return ProcedureOutcome.error("EXECUTION_FAILED", PROCEDURE_FAILURE_MESSAGE);
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
