package com.voxticket.audit;

import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.observability.TraceIds;
import com.voxticket.observability.TurnTrace;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Phase 8 (Conversation Audit), P1 refactor. The single injection point for
 * the durable audit trail - separate from the in-memory ConversationSession
 * that drives the live conversation, and separate from TurnMetrics
 * (aggregate Micrometer counters).
 *
 * <p>P1: writes are asynchronous and NEVER on the conversation critical
 * path. Every public method assembles an immutable {@link AuditEvent} and
 * hands it to {@link AuditEventBus#publish(AuditEvent)} - a bounded
 * in-memory enqueue with no I/O, no transactions, and no blocking. The
 * {@link AuditBatchWriter} persists batches on its own thread. If the queue
 * is full or the database is down, telemetry is dropped (and counted) while
 * the conversation completes normally.
 *
 * <p>Every public method is internally exception-safe: a failed publish must
 * never break the actual conversation.
 *
 * <p>NEVER pass into detail: OTP values, secrets, chain-of-thought, hidden/
 * provider reasoning, or the raw system prompt. Every call site in this
 * codebase only ever passes short structured summaries (tool names,
 * outcome codes, model tiers) - never model-generated free text.
 */
@Service
public class ConversationAuditService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAuditService.class);
    private static final int MAX_DETAIL_LENGTH = 500;

    private final AuditEventBus eventBus;

    public ConversationAuditService(AuditEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Best-effort and non-throwing: enqueues a session-lifecycle marker.
     * {@code lastTurnOutcome} is null for plain touches; turn-completion
     * markers (see {@link #recordTurnCompletion}) carry the outcome so the
     * admin can derive ACTIVE/COMPLETED/ABORTED at read time.
     */
    public void recordSessionTouch(ConversationSession session) {
        publish(() -> new AuditEvent.SessionTouch(
                session.getSessionId(),
                session.getChannel(),
                session.getCustomerIdentity().customerId(),
                session.getCustomerIdentity().assuranceLevel().name(),
                session.isEscalated(),
                session.getTurnCount(),
                null,
                currentTraceId(),
                Instant.now()));
    }

    /**
     * P0 (session lifecycle). Turn-completion marker: carries the turn count
     * and the terminal outcome label ({@code "aborted"} for aborted turns).
     * The writer upserts these onto {@code conversation_sessions}; the admin
     * derives ACTIVE (recent activity), ABORTED (last outcome "aborted") or
     * COMPLETED (otherwise) at read time - no extra writes.
     */
    public void recordTurnCompletion(ConversationSession session, int turnNumber, String outcome, boolean aborted) {
        publish(() -> new AuditEvent.SessionTouch(
                session.getSessionId(),
                session.getChannel(),
                session.getCustomerIdentity().customerId(),
                session.getCustomerIdentity().assuranceLevel().name(),
                session.isEscalated(),
                Math.max(session.getTurnCount(), turnNumber),
                aborted ? "aborted" : outcome,
                currentTraceId(),
                Instant.now()));
    }

    /** Enqueues one per-turn AI decision trace for async persistence. */
    public void recordTurnTrace(TurnTrace trace) {
        publish(() -> new AuditEvent.TurnTraceRecord(trace));
    }

    /** Enqueues one conversation message (full text; the TEXT column is not truncated). */
    public void recordMessage(ConversationSession session, int turnNumber, MessageRole role, String text) {
        publish(() -> new AuditEvent.MessageRecord(
                session.getSessionId(), turnNumber, role.name(), text, currentTraceId(), Instant.now()));
    }

    /** Enqueues one typed audit event; detail is truncated to 500 chars (existing contract). */
    public void recordEvent(ConversationSession session, Integer turnNumber, ConversationEventType type, String detail) {
        publish(() -> new AuditEvent.EventRecord(
                session.getSessionId(), turnNumber, type.name(), truncate(detail), currentTraceId(), Instant.now()));
    }

    private void publish(Supplier<AuditEvent> eventSupplier) {
        try {
            eventBus.publish(eventSupplier.get());
        } catch (Exception e) {
            // The audit path must never break the conversation, whatever happens.
            log.warn("event=audit_publish_failed errorType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * Best-effort trace ID for the current request: the filter/runtime put it
     * in MDC; when absent (background threads, unit tests) generate one so
     * the row is still correlatable. Never throws.
     */
    private static String currentTraceId() {
        try {
            String traceId = MDC.get(TraceIds.MDC_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
        } catch (Exception ignored) {
            // fall through to generation
        }
        return TraceIds.newTraceId();
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
    }
}
