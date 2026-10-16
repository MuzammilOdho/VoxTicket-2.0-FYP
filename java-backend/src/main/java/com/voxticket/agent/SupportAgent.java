package com.voxticket.agent;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.config.TimeoutProperties;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.CommunicationProfile;
import com.voxticket.conversation.ConversationLanguage;
import com.voxticket.conversation.ConversationLanguageResolver;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.CorrectionAntecedent;
import com.voxticket.conversation.DialogueStateTools;
import com.voxticket.conversation.InterruptionSignal;
import com.voxticket.conversation.Obligation;
import com.voxticket.conversation.RecentAction;
import com.voxticket.conversation.RecentActionType;
import com.voxticket.conversation.Slot;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.observability.TurnTrace;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.procedure.DeferredProcedureIntent;
import com.voxticket.procedure.ProcedureControlTools;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.procedure.ProcedureRequestTools;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureStatus;
import com.voxticket.rag.PolicyKnowledgeTools;
import com.voxticket.rag.RagService;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.service.CustomerOrderQueryService;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
public class SupportAgent {

    private static final Logger log = LoggerFactory.getLogger(SupportAgent.class);
    private static final String GENERIC_BLANK_FALLBACK = "Sorry, could you say that again?";
    private static final int MAX_RECENT_EFFECTS = 3;

    // P3.1: the system-prompt base text moved to a versioned template file
    // (prompts/system-v1.txt, loaded by PromptTemplateService). The version
    // is selected by voxticket.prompt.version and recorded on every turn
    // trace; see currentPromptVersion().

    private final TierChatClientRegistry clientRegistry;
    private final ContextBuilder contextBuilder;
    private final ModelSelector modelSelector;
    private final CustomerOrderQueryService queryService;
    private final RagService ragService;
    private final ProcedureCoordinator procedureCoordinator;
    private final TurnMetrics turnMetrics;
    private final ConversationAuditService auditService;
    private final TimeoutProperties timeouts;
    // P3.1: versioned system-prompt template (voxticket.prompt.version).
    private final PromptTemplateService promptTemplates;
    // Phase 5: the deterministic language resolver drives the injected
    // communication profile. The model follows the resolved profile; it
    // never guesses the customer's language.
    private final ConversationLanguageResolver languageResolver;

    public SupportAgent(
            TierChatClientRegistry clientRegistry,
            ContextBuilder contextBuilder,
            ModelSelector modelSelector,
            CustomerOrderQueryService queryService,
            RagService ragService,
            ProcedureCoordinator procedureCoordinator,
            TurnMetrics turnMetrics,
            ConversationAuditService auditService,
            ConversationLanguageResolver languageResolver,
            TimeoutProperties timeouts,
            PromptTemplateService promptTemplates) {
        this.clientRegistry = clientRegistry;
        this.contextBuilder = contextBuilder;
        this.modelSelector = modelSelector;
        this.queryService = queryService;
        this.ragService = ragService;
        this.procedureCoordinator = procedureCoordinator;
        this.turnMetrics = turnMetrics;
        this.auditService = auditService;
        this.languageResolver = languageResolver;
        this.timeouts = timeouts;
        this.promptTemplates = promptTemplates;
    }

    /**
     * P3.1: the exact prompt-template identity this turn's system prompt was
     * built from. Recorded on every turn trace so a trace pins the prompt
     * byte-for-byte (version + SHA-256 of the template content).
     */
    public PromptVersion currentPromptVersion() {
        return promptTemplates.current();
    }


    public AgentResponse respond(ConversationSession session, String currentUserMessage) {
        return respondWithMode(session, currentUserMessage, ToolAccessMode.FULL);
    }

    /**
     * Pass 2D-A: answers a follow-up while a guarded procedure (OTP
     * verification or claim confirmation) is still unresolved. Only customer
     * read / policy tools are registered - procedure-request tools
     * (requestCancellation, requestReturn, requestClaim, requestHumanSupport)
     * are genuinely unavailable, so no second mutation procedure can start.
     * Enforcement is tool registration, not prompting.
     */
    public AgentResponse respondReadOnly(ConversationSession session, String currentUserMessage) {
        return respondWithMode(session, currentUserMessage, ToolAccessMode.READ_ONLY);
    }

    /**
     * Pass 2D-B: answers a follow-up while one procedure is active. The full
     * read / policy / procedure-request surface is available - plus the safe
     * procedure-control tools (abandon active, discard deferred) - so the
     * model can understand a second mutation request, a correction, or a
     * replacement. The coordinator guarantees the safety invariant: an
     * identical request reuses the live procedure, a different request is
     * deferred onto the pending-request stack (P2.2), and a second live
     * procedure can never be created. Enforcement is tool registration plus
     * coordinator state ownership, not prompting.
     */
    public AgentResponse respondGuarded(ConversationSession session, String currentUserMessage) {
        return respondWithMode(session, currentUserMessage, ToolAccessMode.GUARDED);
    }

    /**
     * The tool objects attached to the chat call for a mode. Package-visible
     * for tests: READ_ONLY must never contain a {@code ProcedureRequestTools}
     * instance; GUARDED must contain both {@code ProcedureRequestTools} and
     * {@code ProcedureControlTools}.
     */
    Object[] toolObjectsForMode(ToolAccessMode mode, ConversationSession session) {
        var customerTools = new CustomerReadTools(queryService, session.getCustomerIdentity(), session, turnMetrics, auditService, timeouts);
        var policyTools = new PolicyKnowledgeTools(ragService, turnMetrics, session, auditService, timeouts);
        // P2.1: the dialogue-state proposal tool is registered in every mode -
        // it only proposes state, never executes a business mutation, so it is
        // safe wherever read tools are safe.
        var dialogueTools = new DialogueStateTools(procedureCoordinator, session, timeouts);
        if (mode == ToolAccessMode.READ_ONLY) {
            return new Object[]{customerTools, policyTools, dialogueTools};
        }
        var procedureTools = new ProcedureRequestTools(procedureCoordinator, session, timeouts);
        if (mode == ToolAccessMode.GUARDED) {
            var controlTools = new ProcedureControlTools(procedureCoordinator, session, timeouts);
            return new Object[]{customerTools, policyTools, dialogueTools, procedureTools, controlTools};
        }
        return new Object[]{customerTools, policyTools, dialogueTools, procedureTools};
    }

    /**
     * Streaming variant of {@link #respondWithMode}: identical setup (model
     * selection, history, tools, system prompt), but the model response is
     * consumed as a token stream and each non-empty delta is handed to
     * {@code deltaSink} immediately - so a voice client can start speaking
     * the first sentence while the rest is still generating.
     *
     * <p>{@code deltaSink} must throw {@link TurnAbortedException} (unchecked)
     * when the client disconnected; it propagates unwrapped so the runtime
     * can abort the turn (P1.3: recording exactly what was emitted as a
     * partial assistant message) and release session ownership.
     */
    public AgentResponse respondStream(ConversationSession session, String currentUserMessage,
            ToolAccessMode mode, Consumer<String> deltaSink) {
        long start = System.nanoTime();
        String tierLabel = "UNKNOWN";
        String providerLabel = "unknown";
        String modelLabel = "unknown";
        try {
            long routingStart = System.nanoTime();
            var selection = modelSelector.selectDetailed(session, currentUserMessage);
            double routingMs = (System.nanoTime() - routingStart) / 1_000_000.0;
            tierLabel = selection.tier().name();
            var resolution = clientRegistry.resolutionFor(selection.tier());
            providerLabel = resolution.provider().name();
            modelLabel = resolution.model();
            traceSelection(session, selection, resolution, routingMs, mode);
            turnMetrics.recordModelSelection(tierLabel, providerLabel, modelLabel, selection.reason());
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.MODEL_SELECTED,
                    "tier=" + tierLabel + " provider=" + providerLabel + " model=" + modelLabel + " reason=" + selection.reason());
            log.info("event=model_selected sessionId={} tier={} provider={} model={} reason={}", session.getSessionId(), tierLabel, providerLabel, modelLabel, selection.reason());

            var history = contextBuilder.buildHistory(session, currentUserMessage);
            Object[] tools = toolObjectsForMode(mode, session);
            String systemPrompt = buildSystemPrompt(session);

            log.info("event=support_agent_stream_start sessionId={} tier={} provider={} model={} mode={}", session.getSessionId(), tierLabel, providerLabel, modelLabel, mode);

            // P2: TTFT is the first accepted (non-empty) model delta. The
            // wrapper stamps it once and is otherwise transparent; a blank
            // model response leaves llmTtftMs null (not applicable, never
            // fabricated).
            AtomicBoolean ttftStamped = new AtomicBoolean(false);
            long ttftStart = System.nanoTime();
            Consumer<String> tracingSink = delta -> {
                if (ttftStamped.compareAndSet(false, true)) {
                    TurnTrace.Builder builder = session.getActiveTraceBuilder();
                    if (builder != null) {
                        builder.llmTtftMs((System.nanoTime() - ttftStart) / 1_000_000.0);
                    }
                }
                deltaSink.accept(delta);
            };

            ChatClient chatClient = clientRegistry.clientFor(selection.tier());
            StringBuilder fullText = new StringBuilder();
            final ChatResponse[] lastChunk = new ChatResponse[1];
            for (ChatResponse chunk : chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .tools(tools)
                    .stream()
                    .chatResponse()
                    .toIterable()) {
                lastChunk[0] = chunk;
                String delta = chunk.getResult() == null || chunk.getResult().getOutput() == null
                        ? null
                        : chunk.getResult().getOutput().getText();
                if (delta != null && !delta.isEmpty()) {
                    fullText.append(delta);
                    tracingSink.accept(delta);
                }
            }

            String content = fullText.toString();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            recordTokenUsage(lastChunk[0], providerLabel, modelLabel, session);

            if (content.isBlank()) {
                log.warn("event=support_agent_blank_response sessionId={} tier={} model={}", session.getSessionId(), tierLabel, modelLabel);
                turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                        AgentResponse.Outcome.BLANK_FALLBACK.toLlmMetricLabel());
                traceLlm(session, durationMs, "BLANK_FALLBACK");
                String fallback = blankResponseFallback(session);
                deltaSink.accept(fallback);
                return new AgentResponse(fallback, AgentResponse.Outcome.BLANK_FALLBACK);
            }

            turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                    AgentResponse.Outcome.SUCCESS.toLlmMetricLabel());
            traceLlm(session, durationMs, null);
            log.info("event=support_agent_stream_end sessionId={} outcome=success durationMs={}", session.getSessionId(), durationMs);
            return new AgentResponse(content, AgentResponse.Outcome.SUCCESS);
        } catch (TurnAbortedException aborted) {
            // Client disconnected mid-stream: not a model error. Propagate so
            // the runtime drops the partial reply and releases the lock.
            throw aborted;
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                    AgentResponse.Outcome.MODEL_ERROR.toLlmMetricLabel());
            traceLlm(session, durationMs, "MODEL_ERROR");
            log.error("event=support_agent_stream_end sessionId={} outcome=model_error errorType={} durationMs={}",
                    session.getSessionId(), e.getClass().getSimpleName(), durationMs, e);
            // P0.2: a streaming failure must be audible. The blank-output
            // path above emits its fallback; the error path previously
            // returned the safe text without emitting it, so the caller heard
            // silence while history recorded an apology nobody heard. The
            // sink is the runtime's generation-checked wrapper, so a
            // superseded turn still aborts here instead of speaking - the
            // TurnAbortedException propagates unwrapped like any other emit.
            String errorText = "I'm having trouble processing that right now - please try again in a moment.";
            deltaSink.accept(errorText);
            return new AgentResponse(errorText,
                    AgentResponse.Outcome.MODEL_ERROR);
        }
    }

    private AgentResponse respondWithMode(ConversationSession session, String currentUserMessage, ToolAccessMode mode) {
        long start = System.nanoTime();
        String tierLabel = "UNKNOWN";
        String providerLabel = "unknown";
        String modelLabel = "unknown";
        try {
            long routingStart = System.nanoTime();
            var selection = modelSelector.selectDetailed(session, currentUserMessage);
            double routingMs = (System.nanoTime() - routingStart) / 1_000_000.0;
            tierLabel = selection.tier().name();
            var resolution = clientRegistry.resolutionFor(selection.tier());
            providerLabel = resolution.provider().name();
            modelLabel = resolution.model();
            traceSelection(session, selection, resolution, routingMs, mode);
            turnMetrics.recordModelSelection(tierLabel, providerLabel, modelLabel, selection.reason());
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.MODEL_SELECTED,
                    "tier=" + tierLabel + " provider=" + providerLabel + " model=" + modelLabel + " reason=" + selection.reason());
            log.info("event=model_selected sessionId={} tier={} provider={} model={} reason={}", session.getSessionId(), tierLabel, providerLabel, modelLabel, selection.reason());

            var history = contextBuilder.buildHistory(session, currentUserMessage);
            Object[] tools = toolObjectsForMode(mode, session);
            // Pass 2D-A: the system prompt is identical in both modes. Read-only
            // enforcement is tool registration (toolObjectsForMode), not prompting.
            String systemPrompt = buildSystemPrompt(session);

            log.info("event=support_agent_call_start sessionId={} tier={} provider={} model={} mode={}", session.getSessionId(), tierLabel, providerLabel, modelLabel, mode);

            // No per-prompt advisors: the tool-calling advisor is a default
            // advisor on each tier's ChatClient (configured once in
            // TierChatClientRegistry), so the framework's own default is not
            // auto-registered and each turn runs exactly one ToolCallingAdvisor.
            ChatClient chatClient = clientRegistry.clientFor(selection.tier());
            ChatResponse chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .tools(tools)
                    .call()
                    .chatResponse();

            String content = chatResponse.getResult().getOutput().getText();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            recordTokenUsage(chatResponse, providerLabel, modelLabel, session);

            if (content == null || content.isBlank()) {
                log.warn("event=support_agent_blank_response sessionId={} tier={} model={}", session.getSessionId(), tierLabel, modelLabel);
                turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                        AgentResponse.Outcome.BLANK_FALLBACK.toLlmMetricLabel());
                traceLlm(session, durationMs, "BLANK_FALLBACK");
                return new AgentResponse(blankResponseFallback(session), AgentResponse.Outcome.BLANK_FALLBACK);
            }

            turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                    AgentResponse.Outcome.SUCCESS.toLlmMetricLabel());
            traceLlm(session, durationMs, null);
            log.info("event=support_agent_call_end sessionId={} outcome=success durationMs={}", session.getSessionId(), durationMs);
            return new AgentResponse(content, AgentResponse.Outcome.SUCCESS);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            turnMetrics.recordLlmCall(Duration.ofMillis(durationMs), tierLabel, providerLabel, modelLabel,
                    AgentResponse.Outcome.MODEL_ERROR.toLlmMetricLabel());
            traceLlm(session, durationMs, "MODEL_ERROR");
            log.error("event=support_agent_call_end sessionId={} outcome=model_error errorType={} durationMs={}",
                    session.getSessionId(), e.getClass().getSimpleName(), durationMs, e);
            return new AgentResponse("I'm having trouble processing that right now - please try again in a moment.",
                    AgentResponse.Outcome.MODEL_ERROR);
        }
    }
    String blankResponseFallback(ConversationSession session) {
        // Phase 5: the fallback is customer-facing, so it follows the same
        // resolved language as every other deterministic surface. English
        // output is unchanged; the pending description stays in its stored
        // (English) form inside the localized wrapper.
        ConversationLanguage language = languageResolver.resolve(session);
        Optional<ProcedureState> active = session.getActiveProcedure();
        if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_CONFIRMATION) {
            String pending = active.get().getPendingDescription();
            return switch (language) {
                case URDU -> "معذرت، کیا آپ دوبارہ کہہ سکتے ہیں؟ مجھے اب بھی اس بات کی تصدیق درکار ہے کہ آپ " + pending + " چاہتے ہیں یا نہیں۔";
                case ROMAN_URDU -> "Maaf kijiye, kya aap dobara keh sakte hain? Mujhe ab bhi confirm karna hai ke aap " + pending + " chahte hain ya nahi.";
                case CODE_SWITCH -> "Sorry, kya aap dobara keh sakte hain? I still need a yes or no ke aap " + pending + " chahte hain ya nahi.";
                case ENGLISH -> "Sorry, could you say that again? I still need a yes or no on whether you'd like to " + pending + ".";
            };
        }
        if (active.isPresent() && active.get().getStatus() == ProcedureStatus.AWAITING_VERIFICATION) {
            return switch (language) {
                case URDU -> "معذرت، کیا آپ دوبارہ کہہ سکتے ہیں؟ میں اب بھی تصدیقی کوڈ کا انتظار کر رہا ہوں۔";
                case ROMAN_URDU -> "Maaf kijiye, kya aap dobara keh sakte hain? Main ab bhi verification code ka intezar kar raha hoon.";
                case CODE_SWITCH -> "Sorry, kya aap repeat kar sakte hain? Main ab bhi verification code ka wait kar raha hoon.";
                case ENGLISH -> "Sorry, could you repeat that? I'm still waiting for the verification code.";
            };
        }
        return switch (language) {
            case URDU -> "معذرت، کیا آپ دوبارہ کہہ سکتے ہیں؟";
            case ROMAN_URDU -> "Maaf kijiye, kya aap dobara keh sakte hain?";
            case CODE_SWITCH -> "Sorry, kya aap dobara keh sakte hain?";
            case ENGLISH -> GENERIC_BLANK_FALLBACK;
        };
    }

    /**
     * P2: records the routing + model selection on the turn's trace builder.
     * Pure field assignment; a missing builder (no trace in flight) is a
     * no-op - trace assembly must never break the turn.
     */
    private static void traceSelection(ConversationSession session, ModelSelectionResult selection,
            TierChatClientRegistry.TierResolution resolution, double routingMs, ToolAccessMode mode) {
        TurnTrace.Builder builder = session.getActiveTraceBuilder();
        if (builder == null) {
            return;
        }
        builder.routingMs(routingMs);
        if (selection.strategy() != null) {
            builder.routingStrategy(selection.strategy().name());
        }
        builder.tier(selection.tier().name())
                .routingReason(selection.reason())
                .provider(resolution.provider().name())
                .model(resolution.model())
                .detail("toolAccessMode", mode.name());
        RoutingDecision decision = selection.routingDecision();
        if (decision != null) {
            // Non-semantic decisions carry NaN margins - leave the field null
            // rather than recording a meaningless zero (same rule as metrics).
            if (!Double.isNaN(decision.margin())) {
                builder.semanticMargin(decision.margin());
            }
            if (decision.signals() != null && !decision.signals().isEmpty()) {
                builder.intentSignals(decision.signals().stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining(",")));
            }
        }
    }

    /** P2: records the LLM call duration and, on failures, the error classification. */
    private static void traceLlm(ConversationSession session, long durationMs, String errorCode) {
        TurnTrace.Builder builder = session.getActiveTraceBuilder();
        if (builder == null) {
            return;
        }
        builder.llmTotalMs((double) durationMs);
        if (errorCode != null) {
            builder.errorCode(errorCode);
        }
    }

    private void recordTokenUsage(ChatResponse chatResponse, String provider, String model, ConversationSession session) {
        try {
            var usage = chatResponse == null || chatResponse.getMetadata() == null ? null : chatResponse.getMetadata().getUsage();
            if (usage == null) {
                return;
            }
            TurnTrace.Builder builder = session.getActiveTraceBuilder();
            if (usage.getPromptTokens() != null) {
                turnMetrics.recordTokenUsage(provider, model, "prompt", usage.getPromptTokens());
                if (builder != null) {
                    builder.promptTokens(usage.getPromptTokens());
                }
            }
            if (usage.getCompletionTokens() != null) {
                turnMetrics.recordTokenUsage(provider, model, "completion", usage.getCompletionTokens());
                if (builder != null) {
                    builder.completionTokens(usage.getCompletionTokens());
                }
            }
        } catch (Exception e) {
            log.debug("event=token_usage_unavailable provider={} model={} reason={}", provider, model, e.getClass().getSimpleName());
        }
    }

    String buildSystemPrompt(ConversationSession session) {
        // P3.1: the base text comes from the versioned template
        // (prompts/system-v{version}.txt); only the dynamic injections
        // below are still assembled here.
        StringBuilder prompt = new StringBuilder(promptTemplates.systemPromptBase());

        // Phase 5: the resolved language and its writing profile are
        // injected deterministically - the model follows the profile and
        // never guesses the customer's language.
        prompt.append("\n\n").append(CommunicationProfile.render(languageResolver.resolve(session)));

        String state = buildConversationState(session);
        if (!state.isBlank()) {
            prompt.append("\n\n").append(state);
        }

        return prompt.toString();
    }

    /**
     * Compact dynamic session context. One field per line, only fields that exist are appended, and nothing
     * internal ever enters the model's context: no customer IDs, no SKUs, no OTP/challenge IDs, no raw
     * procedure slot values. This is context for reference resolution - not business authorization.
     */
    String buildConversationState(ConversationSession session) {
        StringBuilder sb = new StringBuilder();
        session.getFocus().ifPresent(f -> {
            if (f.orderNumber() != null) {
                appendStateLine(sb, "focusOrder: " + f.orderNumber());
            }
            // The focus itemSku is internal bookkeeping - only the display name goes to the model.
            if (f.itemDisplayName() != null) {
                appendStateLine(sb, "focusItem: " + f.itemDisplayName());
            }
        });
        session.getActiveProcedure().ifPresent(p -> {
            appendStateLine(sb, "activeProcedure: " + p.getType());
            appendStateLine(sb, "activeProcedureStatus: " + p.getStatus());
            appendStateLine(sb, "activeTarget: " + p.getPendingDescription());
        });
        // P2.2: the pending-request stack renders top-first with
        // customer-visible selectors only - no procedure IDs, no SKUs, no
        // challenge material. The top keeps the established line names so
        // the existing prompt-contract tests hold.
        List<DeferredProcedureIntent> pending = session.getDialogueState().getPendingRequests();
        for (int i = 0; i < pending.size(); i++) {
            DeferredProcedureIntent d = pending.get(i);
            String prefix = i == 0 ? "deferred" : "queuedRequest" + (i + 1);
            appendStateLine(sb, prefix + "Procedure: " + d.type());
            appendStateLine(sb, prefix + "Target: " + d.orderNumber());
            if (d.itemDisplayName() != null) {
                appendStateLine(sb, prefix + "Item: " + d.itemDisplayName());
            }
        }
        // P2.1/P2.4: established slots with confidence and provenance, so the
        // model knows what is certain and what still needs clarification.
        for (Slot slot : session.getDialogueState().getSlots()) {
            appendStateLine(sb, "slot " + slot.name() + "=" + slot.value()
                    + " (confidence " + String.format(Locale.ROOT, "%.2f", slot.confidence())
                    + ", " + slot.provenance().name().toLowerCase(Locale.ROOT) + ")");
        }
        // P2.1: outstanding discourse obligations - things the system owes
        // the caller this turn.
        for (Obligation obligation : session.getDialogueState().getObligations()) {
            appendStateLine(sb, "obligation " + obligation.type().name() + ": " + obligation.detail());
        }
        // P2.1: the most recent correction, so a follow-up can tell "the
        // caller changed the order number" from "the caller gave a new one".
        List<CorrectionAntecedent> corrections = session.getDialogueState().getCorrectionAntecedents();
        if (!corrections.isEmpty()) {
            CorrectionAntecedent latest = corrections.get(0);
            appendStateLine(sb, "lastCorrection: " + latest.slotName() + " was '" + latest.oldValue() + "'");
        }
        // P2.3: the deterministic interruption hint for this turn. The model
        // still proposes the classification via updateDialogueState;
        // deterministic code validates it.
        InterruptionSignal signal = session.getDialogueState().getLastInterruptionSignal();
        if (signal != null && signal.deterministic()) {
            appendStateLine(sb, "interruptionSignal: " + signal.interruptionClass()
                    + " (" + signal.evidence()
                    + (signal.slotName() != null ? ", slot " + signal.slotName() : "") + ")"
                    + " - if you agree, propose it via updateDialogueState CLASSIFY_INTERRUPTION;"
                    + " otherwise propose what you actually hear.");
        }
        List<RecentAction> actions = session.getRecentActions();
        if (!actions.isEmpty()) {
            appendStateLine(sb, "recentEffects:");
            actions.stream()
                    .skip(Math.max(0, actions.size() - MAX_RECENT_EFFECTS))
                    .map(this::describeEffect)
                    .forEach(effect -> appendStateLine(sb, "- " + effect));
            appendStateLine(sb, "recentEffects are historical facts only - use getMyOrderContext or getMyTicketStatus for current state.");
        }
        if (sb.isEmpty()) {
            return "";
        }
        return "CONVERSATION STATE\n" + sb.toString().stripTrailing();
    }

    private static void appendStateLine(StringBuilder sb, String line) {
        sb.append(line).append('\n');
    }

    private String describeEffect(RecentAction action) {
        StringBuilder sb = new StringBuilder(action.type().name());
        switch (action.type()) {
            case ORDER_CANCELLED -> {
                sb.append(' ').append(action.target());
                // Keep the payment consequence as a stable code for a just-completed cancellation, so a
                // follow-up like "will I get a refund?" cannot contradict what was already correctly said.
                if (isPaymentConsequence(action.status())) {
                    sb.append(' ').append(action.status());
                }
            }
            case ESCALATED -> {
                // The target is the constant "SUPPORT" - the ticket reference is the useful part.
                if (action.reference() != null) {
                    sb.append(' ').append(action.reference());
                }
            }
            default -> {
                sb.append(' ').append(action.target());
                if (action.reference() != null) {
                    sb.append(' ').append(action.reference());
                }
            }
        }
        return sb.toString();
    }

    private boolean isPaymentConsequence(String status) {
        if (status == null) {
            return false;
        }
        try {
            PaymentConsequence.valueOf(status);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
