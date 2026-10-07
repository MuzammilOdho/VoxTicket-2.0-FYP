package com.voxticket.conversation;

import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.agent.ToolAccessMode;
import com.voxticket.audit.ConversationAuditService;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TraceIds;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.observability.TurnTrace;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.procedure.ConfirmationDecision;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureOutcome;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.safety.GroqMlPromptGuard;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.NormalizationResult;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.safety.SafeLogging;
import com.voxticket.verification.SensitiveTurn;
import com.voxticket.verification.SensitiveTurnParser;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
        return processTurnInternal(turn, null);
    }

    /**
     * Streaming variant of {@link #processTurn(UserTurn)}: identical branch
     * logic, but LLM-backed branches emit token deltas to {@code deltaSink}
     * as they generate, and deterministic branches emit their full text as
     * one delta. {@code deltaSink} must throw
     * {@link TurnAbortedException} (unchecked) when
     * the client disconnected; it unwinds through the per-session lock,
     * skipping assistant-message recording for the aborted turn.
     */
    public AssistantTurn processTurnStream(UserTurn turn, Consumer<String> deltaSink) {
        if (deltaSink == null) {
            throw new IllegalArgumentException("deltaSink must not be null");
        }
        return processTurnInternal(turn, deltaSink);
    }

    /** Emits a deterministic reply into the stream; no-op when not streaming. */
    private static void emit(Consumer<String> deltaSink, String text) {
        if (deltaSink != null && text != null && !text.isEmpty()) {
            deltaSink.accept(text);
        }
    }

    private AssistantTurn processTurnInternal(UserTurn turn, Consumer<String> deltaSink) {
        long startNanos = System.nanoTime();
        return sessionStore.withSession(turn.sessionId(), turn.channel(), session -> {
            // P0 (correlation): the trace ID arrives via UserTurn provider
            // metadata (populated by the controllers from the traceparent
            // header). A missing/blank value degrades to a fresh ID and never
            // affects execution. MDC carries trace + session on every log line
            // below; it is cleared in the finally.
            String traceId = resolveTraceId(turn);
            MDC.put(TraceIds.MDC_TRACE_ID, traceId);
            MDC.put(TraceIds.MDC_SESSION_ID, session.getSessionId());
            try {
                return processTurnForSession(turn, deltaSink, session, startNanos, traceId);
            } finally {
                MDC.clear();
            }
        });
    }

    /**
     * The turn body, extracted so {@link #processTurnInternal} can own MDC
     * lifecycle around it.
     *
     * <p>Catches {@link TurnAbortedException} to classify the turn explicitly
     * (turn-completion audit marker with outcome {@code "aborted"} plus the
     * {@code voxticket.turn.aborted} metric) before rethrowing - the partial
     * reply is still dropped and the per-session lock still releases via the
     * session store's finally.
     */
    private AssistantTurn processTurnForSession(UserTurn turn, Consumer<String> deltaSink,
            ConversationSession session, long startNanos, String traceId) {
        // Latest turn number, for abort classification: set right after every
        // session.recordUserMessage(...) below.
        int[] currentTurn = new int[]{-1};
        // P2 (turn decision trace): one builder per turn, installed on the
        // session so stage components (agent, RAG, tools, coordinator) can
        // record their observations. recordUserMessage is called exactly once
        // per turn below, so getTurnCount()+1 is the number it will assign.
        // Assembly is pure field assignment; the single publish at turn end
        // goes through the async audit bus. Cleared in the finally so the
        // builder never leaks across turns.
        TurnTrace.Builder traceBuilder = TurnTrace.builder(
                session.getSessionId(), session.getTurnCount() + 1, session.getChannel().name(), traceId, Instant.now());
        session.setActiveTraceBuilder(traceBuilder);
        try {
            if (StringUtils.hasText(turn.callerPhone())) {
                CustomerIdentity resolved = identityService.resolveByPhone(turn.callerPhone());
                session.applyResolvedIdentity(resolved);
            }
            auditService.recordSessionTouch(session);

            log.info("event=turn_start sessionId={} channel={} turnNumber={} identityAssurance={}",
                    session.getSessionId(), session.getChannel(), session.getTurnCount() + 1, session.getCustomerIdentity().assuranceLevel());

            long normalizeStart = System.nanoTime();
            NormalizationResult normalization = inputNormalizer.normalize(turn.text());
            traceBuilder.normalizeMs(nanosToMs(System.nanoTime() - normalizeStart));
            if (!normalization.accepted()) {
                log.info("event=input_rejected sessionId={} reason=INPUT_TOO_LONG length={}", session.getSessionId(), normalization.rejectedLength());
                int turnNumber = session.recordUserMessage("[message rejected - too long: " + normalization.rejectedLength() + " characters]");
                markTurn(currentTurn, turnNumber);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, "[message rejected - too long: " + normalization.rejectedLength() + " characters]");
                session.recordAssistantMessage(TOO_LONG_MESSAGE);
                auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, TOO_LONG_MESSAGE);
                completeTurn(session, turnNumber, startNanos, "input_too_long");
                emit(deltaSink, TOO_LONG_MESSAGE);
                return new AssistantTurn(TOO_LONG_MESSAGE, false, false, stateView(session, turnNumber), Map.of("rejectionReason", "INPUT_TOO_LONG"));
            }

            String normalizedText = normalization.text();

            String responseText;
            int turnNumber;
            String outcomeLabel;
            Map<String, String> turnMetadata = Map.of();
            Optional<ProcedureState> active = session.getActiveProcedure();

            // Phase 1B: while a verification challenge is pending, parse and
            // redact BEFORE the prompt guard runs. An ML-backed guard is a
            // remote call, so OTP plaintext must never reach it - the guard
            // only ever sees the redacted text. The raw code survives solely
            // in the in-memory SensitiveTurn handed to the local verification
            // logic below. Non-verification turns keep the current behavior.
            SensitiveTurn preParsedSensitive = null;
            String guardInput = normalizedText;
            if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_VERIFICATION) {
                preParsedSensitive = sensitiveTurnParser.parse(normalizedText);
                guardInput = preParsedSensitive.redactedText();
            }

            long guardStart = System.nanoTime();
            PromptGuardVerdict verdict = promptGuard.evaluate(guardInput);
            traceBuilder.guardMs(nanosToMs(System.nanoTime() - guardStart));
            recordGuardVerdict(traceBuilder, verdict);
            if (verdict.suspicious()) {
                log.warn("event=input_blocked sessionId={} category={} inputLength={} inputHash={}",
                        session.getSessionId(), verdict.category(), normalizedText.length(), SafeLogging.hash(normalizedText));
                turnNumber = session.recordUserMessage(REDACTED_FLAGGED_PLACEHOLDER);
                markTurn(currentTurn, turnNumber);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, REDACTED_FLAGGED_PLACEHOLDER);
                auditService.recordEvent(session, turnNumber, ConversationEventType.SAFETY_BLOCKED, "category=" + verdict.category());
                session.recordAssistantMessage(SAFE_DEFLECTION_MESSAGE);
                auditService.recordMessage(session, turnNumber, MessageRole.ASSISTANT, SAFE_DEFLECTION_MESSAGE);
                completeTurn(session, turnNumber, startNanos, "blocked");
                emit(deltaSink, SAFE_DEFLECTION_MESSAGE);
                return new AssistantTurn(SAFE_DEFLECTION_MESSAGE, false, false, stateView(session, turnNumber), Map.of());
            }

            if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_VERIFICATION) {
                // Parsed above, before the prompt guard ran: reuse it here so
                // the turn is parsed exactly once and OTP plaintext never
                // entered history, audit, model input, or the guard.
                SensitiveTurn sensitive = preParsedSensitive;
                log.info("event=sensitive_turn sessionId={} otpCandidatePresent={} multipleCandidates={} resendRequested={} residualPresent={}",
                        session.getSessionId(), sensitive.hasOtpCandidate(), sensitive.multipleCandidates(),
                        sensitive.resendRequested(), sensitive.hasResidual());
                String historyText = sensitive.redactedText();
                turnNumber = session.recordUserMessage(historyText);
                markTurn(currentTurn, turnNumber);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, historyText);

                if (sensitive.multipleCandidates()) {
                    // Never guess between distinct codes: ask for one code and submit nothing.
                    ProcedureOutcome ambiguous = ProcedureOutcome.error("OTP_AMBIGUOUS", "multiple verification codes in one message");
                    responseText = renderDirect(session, ambiguous);
                    // Pass 2C security cleanup: the AssistantTurn boundary is
                    // customer-facing, so only allowlisted safe fields cross it.
                    turnMetadata = safeDirectTurnMetadata(ambiguous);
                    outcomeLabel = "verification_ambiguous";
                    emit(deltaSink, responseText);
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
                    emit(deltaSink, responseText);
                    if (sensitive.hasResidual()) {
                        // Pass 2D-B: GUARDED while a procedure is still live
                        // (possibly the freshly promoted one); FULL only when
                        // no procedure remains. The coordinator makes a second
                        // live procedure impossible, so guarded turns no longer
                        // need to be read-only.
                        AgentResponse residual = isGuardedProcedureActive(session)
                                ? respondGuardedViaAgent(session, sensitive.residualText(), deltaSink)
                                : respondViaAgent(session, sensitive.residualText(), deltaSink);
                        responseText = combineResponses(responseText, residual.text());
                    }
                } else if (sensitive.resendRequested()) {
                    ProcedureOutcome outcome = procedureCoordinator.resendVerificationCode(session);
                    responseText = renderDirect(session, outcome);
                    turnMetadata = safeDirectTurnMetadata(outcome);
                    outcomeLabel = "verification";
                    emit(deltaSink, responseText);
                    if (sensitive.hasResidual()) {
                        // The guarded verification is still unresolved.
                        AgentResponse residual = respondGuardedViaAgent(session, sensitive.residualText(), deltaSink);
                        responseText = combineResponses(responseText, residual.text());
                    }
                } else {
                    // Pass 2D-B: a guarded verification is unresolved - the
                    // model gets GUARDED tools so it can understand a second
                    // mutation request (deferred by the coordinator), a
                    // correction, or an explicit replacement. A second live
                    // procedure is impossible by coordinator construction.
                    AgentResponse agentResponse = respondGuardedViaAgent(session, normalizedText, deltaSink);
                    responseText = agentResponse.text();
                    outcomeLabel = agentResponse.outcome().toTurnLabel("verification_unclear");
                }
            } else if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_CONFIRMATION) {
                turnNumber = session.recordUserMessage(normalizedText);
                markTurn(currentTurn, turnNumber);
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
                        unclearResponse = respondGuardedViaAgent(session, normalizedText, deltaSink);
                        yield unclearResponse.text();
                    }
                };
                if (unclearResponse == null) {
                    emit(deltaSink, responseText);
                }
                outcomeLabel = unclearResponse != null
                        ? unclearResponse.outcome().toTurnLabel("confirmation_unclear")
                        : "confirmation_" + decision.name().toLowerCase();
            } else {
                turnNumber = session.recordUserMessage(normalizedText);
                markTurn(currentTurn, turnNumber);
                auditService.recordMessage(session, turnNumber, MessageRole.USER, normalizedText);
                // Pass 2D-B: a queued deferred intent still needs guarding
                // even with no live procedure (e.g. after abandonment) - the
                // model gets the control tools so it can resolve the
                // contested slot explicitly instead of the coordinator
                // silently deciding the queued request's fate.
                AgentResponse agentResponse = session.getDeferredIntent().isPresent()
                        ? respondGuardedViaAgent(session, normalizedText, deltaSink)
                        : respondViaAgent(session, normalizedText, deltaSink);
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
        } catch (TurnAbortedException aborted) {
            // P0 (abort classification): the voice client disconnected
            // mid-turn (barge-in / hang-up). Previously the abort was only
            // inferable from a missing assistant message; now it is recorded
            // explicitly. The partial reply stays dropped (never recorded)
            // and the exception keeps propagating so the SSE controller can
            // complete the emitter quietly.
            int abortedTurn = currentTurn[0];
            // P2: the aborted turn still gets its decision trace, marked
            // aborted - whatever stages ran before the disconnect are kept.
            finishTrace(session, traceBuilder, "aborted", true);
            auditService.recordTurnCompletion(session, abortedTurn, "aborted", true);
            turnMetrics.recordTurnAborted(session.getChannel().name());
            log.warn("event=turn_aborted sessionId={} turnNumber={} channel={} traceId={}",
                    session.getSessionId(), abortedTurn, session.getChannel(), traceId);
            throw aborted;
        } catch (RuntimeException unexpected) {
            // P2: an unexpected failure must not silently drop the turn's
            // decision trace. Publish whatever stages were captured with an
            // error outcome and the exception type as the error code, then
            // rethrow so the caller still sees the original failure.
            int failedTurn = currentTurn[0];
            traceBuilder.errorCode(unexpected.getClass().getSimpleName());
            finishTrace(session, traceBuilder, "error", false);
            auditService.recordTurnCompletion(session, failedTurn, "error", false);
            log.warn("event=turn_unexpected_error sessionId={} turnNumber={} channel={} traceId={} errorType={}",
                    session.getSessionId(), failedTurn, session.getChannel(), traceId,
                    unexpected.getClass().getSimpleName());
            throw unexpected;
        } finally {
            session.setActiveTraceBuilder(null);
        }
    }

    /**
     * P2: records the prompt-guard verdict on the turn's trace builder. The
     * implementation label is derived honestly from the wired bean: the ML
     * guard flags with category {@code "ML_CLASSIFIER"}; any other suspicious
     * category on the ML bean means the heuristic fallback produced the
     * verdict. An allowed verdict carries no fallback evidence, so fallback
     * stays false rather than guessed.
     */
    private void recordGuardVerdict(TurnTrace.Builder builder, PromptGuardVerdict verdict) {
        builder.guardSuspicious(verdict.suspicious());
        if (verdict.category() != null) {
            builder.guardCategory(verdict.category());
        }
        if (promptGuard instanceof GroqMlPromptGuard) {
            builder.guardImplementation("ml");
            builder.guardFallback(verdict.suspicious() && !"ML_CLASSIFIER".equals(verdict.category()));
        } else {
            builder.guardImplementation("heuristic");
            builder.guardFallback(false);
        }
    }

    /**
     * P2: finalizes and publishes the turn's decision trace. Called exactly
     * once per turn: from {@link #completeTurn} on completion, and from the
     * abort catch for aborted turns. Publishing goes through the async audit
     * bus - it never blocks the turn, and a bus failure is contained here so
     * it can never break the response.
     */
    private void finishTrace(ConversationSession session, TurnTrace.Builder builder, String outcome, boolean aborted) {
        if (builder == null) {
            return;
        }
        try {
            builder.endedAt(Instant.now()).outcome(outcome).aborted(aborted);
            // Language is resolved at turn end, when the session history
            // already contains this turn's user message.
            try {
                builder.language(languageResolver.resolve(session).name());
            } catch (Exception ignored) {
                // Language resolution must never break trace publishing.
            }
            builder.intent(resolveIntent(session, builder));
            if (!builder.tools().isEmpty()) {
                builder.toolMs(builder.tools().stream().mapToDouble(TurnTrace.ToolCall::durationMs).sum());
            }
            auditService.recordTurnTrace(builder.build());
        } catch (Exception e) {
            log.warn("event=turn_trace_publish_failed sessionId={} errorType={}",
                    session.getSessionId(), e.getClass().getSimpleName());
        }
    }

    /**
     * P2: best-effort intent label. There is no explicit intent classifier in
     * the codebase; the closest honest signals are the live procedure (a
     * customer mutation intent) and the tools the turn actually invoked.
     */
    private static String resolveIntent(ConversationSession session, TurnTrace.Builder builder) {
        Optional<ProcedureState> active = session.getActiveProcedure();
        if (active.isPresent()) {
            return active.get().getType().name();
        }
        if (!builder.tools().isEmpty()) {
            return "tool:" + builder.tools().get(0).name();
        }
        return "general_query";
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
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
     *
     * <p>When {@code deltaSink} is non-null the agent streams token deltas to
     * it; {@code null} keeps the classic blocking behavior.
     */
    private AgentResponse respondGuardedViaAgent(ConversationSession session, String text, Consumer<String> deltaSink) {
        session.resetToolInvokedFlag();
        AgentResponse response = deltaSink == null
                ? supportAgent.respondGuarded(session, text)
                : supportAgent.respondStream(session, text, ToolAccessMode.GUARDED, deltaSink);
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
    private AgentResponse respondViaAgent(ConversationSession session, String normalizedText, Consumer<String> deltaSink) {
        session.resetToolInvokedFlag();
        AgentResponse response = deltaSink == null
                ? supportAgent.respond(session, normalizedText)
                : supportAgent.respondStream(session, normalizedText, ToolAccessMode.FULL, deltaSink);
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
        // P1: the session-lifecycle marker (turn count + last outcome) is
        // enqueued for the async audit writer - never written inline.
        auditService.recordTurnCompletion(session, turnNumber, outcome, false);
        // P2: publish the turn's decision trace (async, non-blocking).
        finishTrace(session, session.getActiveTraceBuilder(), outcome, false);
    }

    /**
     * P0 (correlation). Resolves the trace ID from the turn's provider
     * metadata (populated by the controllers from the {@code traceparent}
     * header). A missing or blank value degrades to a freshly generated ID -
     * missing correlation data never affects request execution. Never throws.
     */
    private static String resolveTraceId(UserTurn turn) {
        try {
            Map<String, String> metadata = turn.providerMetadata();
            String candidate = metadata == null ? null : metadata.get(TraceIds.METADATA_TRACE_ID);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        } catch (Exception ignored) {
            // fall through to generation
        }
        return TraceIds.newTraceId();
    }

    /** Records the latest turn number for abort classification and MDC. Never throws. */
    private static void markTurn(int[] currentTurn, int turnNumber) {
        currentTurn[0] = turnNumber;
        try {
            MDC.put(TraceIds.MDC_TURN, String.valueOf(turnNumber));
        } catch (Exception ignored) {
            // MDC must never break the turn.
        }
    }

    private ConversationStateView stateView(ConversationSession session, int turnNumber) {
        return new ConversationStateView(session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel(), turnNumber);
    }
}
