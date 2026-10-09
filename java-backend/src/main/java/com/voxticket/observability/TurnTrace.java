package com.voxticket.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable per-turn AI decision trace.
 *
 * <p>Assembled in-memory during {@code ConversationRuntime.processTurn} by the
 * stage components (guard, router, agent, RAG, tools, coordinator) and
 * persisted asynchronously by the audit batch writer into
 * {@code conversation_turn_traces}. Never blocks the turn: assembly is pure
 * field assignment; persistence goes through the bounded
 * {@code AuditEventBus}.
 *
 * <p>Privacy contract (enforced by construction - there are no fields for
 * them): never carries OTP values, secrets, system prompts,
 * chain-of-thought/reasoning, or full RAG document contents. RAG documents
 * are referenced by id/category/similarity only.
 *
 * <p>Latency fields are {@link Double} milliseconds; {@code null} means "not
 * measured / not applicable for this turn" - never fabricate a zero.
 */
public final class TurnTrace {

    /** RAG document reference: identity + relevance only, never content. */
    public record RagDoc(String docId, String category, double similarity) {}

    /** One tool invocation observed during the turn. */
    public record ToolCall(String name, String result, double durationMs) {}

    private final String sessionId;
    private final int turnNumber;
    private final String channel;
    private final String traceId;
    private final String parentTraceId;
    private final Instant startedAt;
    private final Instant endedAt;
    private final String outcome;
    private final boolean aborted;

    private final Double normalizeMs;
    private final Double guardMs;
    private final Double routingMs;
    private final Double llmTtftMs;
    private final Double llmTotalMs;
    private final Double ragMs;
    private final Double toolMs;

    private final Boolean guardSuspicious;
    private final String guardCategory;
    private final String guardImplementation;
    private final Boolean guardFallback;

    private final String language;
    private final String intent;
    private final String intentSignals;

    private final String routingStrategy;
    private final String tier;
    private final String routingReason;
    private final Double semanticMargin;

    private final String provider;
    private final String model;
    private final Integer promptTokens;
    private final Integer completionTokens;

    private final Boolean ragCacheHit;
    private final List<RagDoc> ragDocs;

    private final List<ToolCall> tools;

    private final String procedureType;
    private final String procedureStatus;
    private final String procedureOutcomeCode;

    private final String errorCode;

    private final Map<String, Object> decision;

    private TurnTrace(Builder b) {
        this.sessionId = b.sessionId;
        this.turnNumber = b.turnNumber;
        this.channel = b.channel;
        this.traceId = b.traceId;
        this.parentTraceId = b.parentTraceId;
        this.startedAt = b.startedAt;
        this.endedAt = b.endedAt;
        this.outcome = b.outcome;
        this.aborted = b.aborted;
        this.normalizeMs = b.normalizeMs;
        this.guardMs = b.guardMs;
        this.routingMs = b.routingMs;
        this.llmTtftMs = b.llmTtftMs;
        this.llmTotalMs = b.llmTotalMs;
        this.ragMs = b.ragMs;
        this.toolMs = b.toolMs;
        this.guardSuspicious = b.guardSuspicious;
        this.guardCategory = b.guardCategory;
        this.guardImplementation = b.guardImplementation;
        this.guardFallback = b.guardFallback;
        this.language = b.language;
        this.intent = b.intent;
        this.intentSignals = b.intentSignals;
        this.routingStrategy = b.routingStrategy;
        this.tier = b.tier;
        this.routingReason = b.routingReason;
        this.semanticMargin = b.semanticMargin;
        this.provider = b.provider;
        this.model = b.model;
        this.promptTokens = b.promptTokens;
        this.completionTokens = b.completionTokens;
        this.ragCacheHit = b.ragCacheHit;
        this.ragDocs = List.copyOf(b.ragDocs);
        this.tools = List.copyOf(b.tools);
        this.procedureType = b.procedureType;
        this.procedureStatus = b.procedureStatus;
        this.procedureOutcomeCode = b.procedureOutcomeCode;
        this.errorCode = b.errorCode;
        this.decision = Map.copyOf(b.decision);
    }

    public static Builder builder(String sessionId, int turnNumber, String channel, String traceId, Instant startedAt) {
        return new Builder(sessionId, turnNumber, channel, traceId, startedAt);
    }

    public String sessionId() { return sessionId; }
    public int turnNumber() { return turnNumber; }
    public String channel() { return channel; }
    public String traceId() { return traceId; }
    public String parentTraceId() { return parentTraceId; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public String outcome() { return outcome; }
    public boolean aborted() { return aborted; }
    public Double normalizeMs() { return normalizeMs; }
    public Double guardMs() { return guardMs; }
    public Double routingMs() { return routingMs; }
    public Double llmTtftMs() { return llmTtftMs; }
    public Double llmTotalMs() { return llmTotalMs; }
    public Double ragMs() { return ragMs; }
    public Double toolMs() { return toolMs; }
    public Boolean guardSuspicious() { return guardSuspicious; }
    public String guardCategory() { return guardCategory; }
    public String guardImplementation() { return guardImplementation; }
    public Boolean guardFallback() { return guardFallback; }
    public String language() { return language; }
    public String intent() { return intent; }
    public String intentSignals() { return intentSignals; }
    public String routingStrategy() { return routingStrategy; }
    public String tier() { return tier; }
    public String routingReason() { return routingReason; }
    public Double semanticMargin() { return semanticMargin; }
    public String provider() { return provider; }
    public String model() { return model; }
    public Integer promptTokens() { return promptTokens; }
    public Integer completionTokens() { return completionTokens; }
    public Boolean ragCacheHit() { return ragCacheHit; }
    public List<RagDoc> ragDocs() { return ragDocs; }
    public List<ToolCall> tools() { return tools; }
    public String procedureType() { return procedureType; }
    public String procedureStatus() { return procedureStatus; }
    public String procedureOutcomeCode() { return procedureOutcomeCode; }
    public String errorCode() { return errorCode; }
    public Map<String, Object> decision() { return decision; }

    /** Mutable accumulator used during turn processing; {@link #build()} freezes it. */
    public static final class Builder {
        private final String sessionId;
        private final int turnNumber;
        private final String channel;
        private final String traceId;
        private final Instant startedAt;

        private String parentTraceId;
        private Instant endedAt;
        private String outcome;
        private boolean aborted;

        private Double normalizeMs;
        private Double guardMs;
        private Double routingMs;
        private Double llmTtftMs;
        private Double llmTotalMs;
        private Double ragMs;
        private Double toolMs;

        private Boolean guardSuspicious;
        private String guardCategory;
        private String guardImplementation;
        private Boolean guardFallback;

        private String language;
        private String intent;
        private String intentSignals;

        private String routingStrategy;
        private String tier;
        private String routingReason;
        private Double semanticMargin;

        private String provider;
        private String model;
        private Integer promptTokens;
        private Integer completionTokens;

        private Boolean ragCacheHit;
        private final List<RagDoc> ragDocs = new ArrayList<>();

        private final List<ToolCall> tools = new ArrayList<>();

        private String procedureType;
        private String procedureStatus;
        private String procedureOutcomeCode;

        private String errorCode;

        private final Map<String, Object> decision = new LinkedHashMap<>();

        private Builder(String sessionId, int turnNumber, String channel, String traceId, Instant startedAt) {
            this.sessionId = sessionId;
            this.turnNumber = turnNumber;
            this.channel = channel;
            this.traceId = traceId;
            this.startedAt = startedAt;
        }

        public Builder parentTraceId(String v) { this.parentTraceId = v; return this; }
        public Builder endedAt(Instant v) { this.endedAt = v; return this; }
        public Builder outcome(String v) { this.outcome = v; return this; }
        public Builder aborted(boolean v) { this.aborted = v; return this; }

        public Builder normalizeMs(Double v) { this.normalizeMs = v; return this; }
        public Builder guardMs(Double v) { this.guardMs = v; return this; }
        public Builder routingMs(Double v) { this.routingMs = v; return this; }
        public Builder llmTtftMs(Double v) { this.llmTtftMs = v; return this; }
        public Builder llmTotalMs(Double v) { this.llmTotalMs = v; return this; }
        public Builder ragMs(Double v) { this.ragMs = v; return this; }
        public Builder toolMs(Double v) { this.toolMs = v; return this; }

        public Builder guardSuspicious(Boolean v) { this.guardSuspicious = v; return this; }
        public Builder guardCategory(String v) { this.guardCategory = v; return this; }
        public Builder guardImplementation(String v) { this.guardImplementation = v; return this; }
        public Builder guardFallback(Boolean v) { this.guardFallback = v; return this; }

        public Builder language(String v) { this.language = v; return this; }
        public Builder intent(String v) { this.intent = v; return this; }
        public Builder intentSignals(String v) { this.intentSignals = v; return this; }

        public Builder routingStrategy(String v) { this.routingStrategy = v; return this; }
        public Builder tier(String v) { this.tier = v; return this; }
        public Builder routingReason(String v) { this.routingReason = v; return this; }
        public Builder semanticMargin(Double v) { this.semanticMargin = v; return this; }

        public Builder provider(String v) { this.provider = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder promptTokens(Integer v) { this.promptTokens = v; return this; }
        public Builder completionTokens(Integer v) { this.completionTokens = v; return this; }

        public Builder ragCacheHit(Boolean v) { this.ragCacheHit = v; return this; }
        public Builder addRagDoc(String docId, String category, double similarity) {
            this.ragDocs.add(new RagDoc(docId, category, similarity));
            return this;
        }

        public Builder addToolCall(String name, String result, double durationMs) {
            this.tools.add(new ToolCall(name, result, durationMs));
            return this;
        }

        /** Read view of the tool calls recorded so far (used by the runtime for intent/toolMs derivation). */
        public List<ToolCall> tools() {
            return List.copyOf(tools);
        }

        public Builder procedureType(String v) { this.procedureType = v; return this; }
        public Builder procedureStatus(String v) { this.procedureStatus = v; return this; }
        public Builder procedureOutcomeCode(String v) { this.procedureOutcomeCode = v; return this; }

        public Builder errorCode(String v) { this.errorCode = v; return this; }

        /** Overflow detail. Callers must not put OTP/secrets/prompts/reasoning here. */
        public Builder detail(String key, Object value) {
            this.decision.put(key, value);
            return this;
        }

        public TurnTrace build() {
            return new TurnTrace(this);
        }
    }
}
