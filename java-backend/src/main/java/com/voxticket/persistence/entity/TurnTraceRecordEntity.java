package com.voxticket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-side mapping of {@code conversation_turn_traces} (V9) - the durable
 * per-turn AI decision trace. Written asynchronously by the audit batch
 * writer; read only by admin queries. JSONB columns are exposed as raw JSON
 * text and parsed by the admin service layer.
 */
@Entity
@Table(name = "conversation_turn_traces")
public class TurnTraceRecordEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ConversationSessionRecord session;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @Column(name = "trace_id", nullable = false, length = 32)
    private String traceId;

    @Column(name = "parent_trace_id", length = 32)
    private String parentTraceId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "outcome", length = 40)
    private String outcome;

    @Column(name = "aborted", nullable = false)
    private boolean aborted;

    @Column(name = "normalize_ms")
    private Double normalizeMs;

    @Column(name = "guard_ms")
    private Double guardMs;

    @Column(name = "routing_ms")
    private Double routingMs;

    @Column(name = "llm_ttft_ms")
    private Double llmTtftMs;

    @Column(name = "llm_total_ms")
    private Double llmTotalMs;

    @Column(name = "rag_ms")
    private Double ragMs;

    @Column(name = "tool_ms")
    private Double toolMs;

    @Column(name = "guard_suspicious")
    private Boolean guardSuspicious;

    @Column(name = "guard_category", length = 80)
    private String guardCategory;

    @Column(name = "guard_implementation", length = 20)
    private String guardImplementation;

    @Column(name = "guard_fallback")
    private Boolean guardFallback;

    @Column(name = "language", length = 10)
    private String language;

    @Column(name = "intent", length = 80)
    private String intent;

    @Column(name = "intent_signals", length = 500)
    private String intentSignals;

    @Column(name = "routing_strategy", length = 20)
    private String routingStrategy;

    @Column(name = "tier", length = 10)
    private String tier;

    @Column(name = "routing_reason", length = 60)
    private String routingReason;

    @Column(name = "semantic_margin")
    private Double semanticMargin;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "rag_cache_hit")
    private Boolean ragCacheHit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rag_docs", columnDefinition = "jsonb")
    private String ragDocs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools", columnDefinition = "jsonb")
    private String tools;

    @Column(name = "procedure_type", length = 40)
    private String procedureType;

    @Column(name = "procedure_status", length = 40)
    private String procedureStatus;

    @Column(name = "procedure_outcome_code", length = 60)
    private String procedureOutcomeCode;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision", columnDefinition = "jsonb")
    private String decision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TurnTraceRecordEntity() {
        // JPA
    }

    public ConversationSessionRecord getSession() { return session; }
    public int getTurnNumber() { return turnNumber; }
    public String getChannel() { return channel; }
    public String getTraceId() { return traceId; }
    public String getParentTraceId() { return parentTraceId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String getOutcome() { return outcome; }
    public boolean isAborted() { return aborted; }
    public Double getNormalizeMs() { return normalizeMs; }
    public Double getGuardMs() { return guardMs; }
    public Double getRoutingMs() { return routingMs; }
    public Double getLlmTtftMs() { return llmTtftMs; }
    public Double getLlmTotalMs() { return llmTotalMs; }
    public Double getRagMs() { return ragMs; }
    public Double getToolMs() { return toolMs; }
    public Boolean getGuardSuspicious() { return guardSuspicious; }
    public String getGuardCategory() { return guardCategory; }
    public String getGuardImplementation() { return guardImplementation; }
    public Boolean getGuardFallback() { return guardFallback; }
    public String getLanguage() { return language; }
    public String getIntent() { return intent; }
    public String getIntentSignals() { return intentSignals; }
    public String getRoutingStrategy() { return routingStrategy; }
    public String getTier() { return tier; }
    public String getRoutingReason() { return routingReason; }
    public Double getSemanticMargin() { return semanticMargin; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Boolean getRagCacheHit() { return ragCacheHit; }
    public String getRagDocs() { return ragDocs; }
    public String getTools() { return tools; }
    public String getProcedureType() { return procedureType; }
    public String getProcedureStatus() { return procedureStatus; }
    public String getProcedureOutcomeCode() { return procedureOutcomeCode; }
    public String getErrorCode() { return errorCode; }
    public String getDecision() { return decision; }
    public Instant getCreatedAt() { return createdAt; }
}
