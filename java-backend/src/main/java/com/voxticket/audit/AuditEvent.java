package com.voxticket.audit;

import com.voxticket.conversation.Channel;
import com.voxticket.observability.TurnTrace;
import java.time.Instant;
import java.util.UUID;

/**
 * P1 (non-blocking audit). The unit of telemetry handed from the
 * conversation path to the asynchronous {@link AuditBatchWriter}.
 *
 * <p>Events are immutable value objects assembled on the request thread with
 * zero I/O. {@link AuditEventBus#publish(AuditEvent)} only enqueues them;
 * persistence happens later on the writer thread. Every event carries the
 * moment it occurred ({@link #occurredAt()}), which the writer binds to the
 * row's timestamp column - so per-session ordering is by occurrence, not by
 * when the writer happened to flush.
 *
 * <p>Privacy contract (same as {@link ConversationAuditService}): no record
 * may carry OTP values, secrets, system prompts, chain-of-thought/reasoning,
 * or full RAG document contents.
 */
public sealed interface AuditEvent
        permits AuditEvent.SessionTouch,
                AuditEvent.MessageRecord,
                AuditEvent.EventRecord,
                AuditEvent.TurnTraceRecord,
                AuditEvent.VoiceCallRecord,
                AuditEvent.VoiceTurnMetricRecord,
                AuditEvent.WorkerHeartbeat {

    /** When the event occurred on the request thread (bound to the DB row's timestamp). */
    Instant occurredAt();

    /**
     * Session lifecycle marker: upsert of the {@code conversation_sessions}
     * row (customer, assurance, escalation, turn count, last outcome).
     * {@code lastTurnOutcome} is null for plain touches and set by
     * turn-completion markers.
     */
    record SessionTouch(
            String sessionId,
            Channel channel,
            UUID customerId,
            String assurance,
            boolean escalated,
            int turnCount,
            String lastTurnOutcome,
            String traceId,
            Instant occurredAt) implements AuditEvent {}

    /** One conversation message (full text; the TEXT column is not truncated). */
    record MessageRecord(
            String sessionId,
            int turnNumber,
            String role,
            String text,
            String traceId,
            Instant occurredAt) implements AuditEvent {}

    /** One typed audit event (detail truncated to 500 chars at publish time). */
    record EventRecord(
            String sessionId,
            Integer turnNumber,
            String eventType,
            String detail,
            String traceId,
            Instant occurredAt) implements AuditEvent {}

    /** One per-turn AI decision trace (see {@link TurnTrace}). */
    record TurnTraceRecord(TurnTrace trace) implements AuditEvent {
        public TurnTraceRecord {
            if (trace == null) {
                throw new IllegalArgumentException("trace must not be null");
            }
        }

        @Override
        public Instant occurredAt() {
            return trace.endedAt() != null ? trace.endedAt() : trace.startedAt();
        }
    }

    /** Voice call lifecycle: one record per LiveKit call (room == Java sessionId). */
    record VoiceCallRecord(
            String room,
            String sessionId,
            String traceId,
            String sttProvider,
            String sttModel,
            String ttsProvider,
            String ttsModel,
            Instant startedAt,
            Instant endedAt,
            String outcome,
            int bargeInCount,
            String disconnectReason,
            String workerId,
            Instant occurredAt) implements AuditEvent {}

    /**
     * Voice per-turn stage latencies emitted by the Python worker. Latencies
     * are null when not measured - never fabricated. Provider/model fields
     * are also applied to the parent call row (the per-turn table carries no
     * provider columns by schema design).
     */
    record VoiceTurnMetricRecord(
            String room,
            int turnNumber,
            String traceId,
            Double sttLatencyMs,
            Double brainTtftMs,
            Double ttsFirstAudioMs,
            Double e2eMs,
            boolean aborted,
            boolean bargeIn,
            String sttLanguage,
            String sttProvider,
            String sttModel,
            String ttsProvider,
            String ttsModel,
            String error,
            Instant occurredAt) implements AuditEvent {}

    /** Python voice worker liveness signal (upserted by worker id). */
    record WorkerHeartbeat(
            String workerId,
            String sttProvider,
            String ttsProvider,
            int activeRooms,
            Instant seenAt) implements AuditEvent {
        @Override
        public Instant occurredAt() {
            return seenAt;
        }
    }
}
