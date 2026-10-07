package com.voxticket.audit;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P1 (non-blocking audit). The only component that writes telemetry to
 * PostgreSQL. It runs on a single dedicated daemon thread
 * ({@code audit-writer}) that drains the {@link AuditEventBus} on a fixed
 * delay and persists each batch with plain JDBC batch statements.
 *
 * <p>Failure isolation (the whole point of this class):
 * <ul>
 *   <li>A failed batch never throws out of {@link #drain()}: the batch is
 *       counted in {@code voxticket.telemetry.dropped{reason=write_error}}
 *       and skipped. The conversation that produced the events already
 *       completed - telemetry loss is acceptable, request failure is not.</li>
 *   <li>No stack-trace spam: one warn line per failed batch plus a 500ms
 *       backoff, so a dead telemetry DB degrades to a quiet counter.</li>
 *   <li>The writer never participates in business transactions: it uses
 *       {@link JdbcTemplate} directly, with no connection to the JPA
 *       transaction the conversation ran in.</li>
 * </ul>
 *
 * <p>Ordering: exactly one writer thread drains one FIFO queue, so events
 * are persisted in publish order globally - and therefore per
 * session/turn. Within a batch, session upserts are applied before the
 * message/event/trace rows that reference them; every row's timestamp is
 * the event's {@code occurredAt} (when it happened), not the flush time.
 */
@Component
public class AuditBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditBatchWriter.class);

    private static final String UPSERT_SESSION_SQL = """
            INSERT INTO conversation_sessions
                (id, session_id, channel, customer_id, identity_assurance, escalated,
                 started_at, last_activity_at, turn_count, last_turn_outcome)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (session_id) DO UPDATE SET
                customer_id = COALESCE(EXCLUDED.customer_id, conversation_sessions.customer_id),
                identity_assurance = EXCLUDED.identity_assurance,
                escalated = (conversation_sessions.escalated OR EXCLUDED.escalated),
                last_activity_at = EXCLUDED.last_activity_at,
                turn_count = EXCLUDED.turn_count,
                last_turn_outcome = COALESCE(EXCLUDED.last_turn_outcome, conversation_sessions.last_turn_outcome)
            """;

    private static final String INSERT_TURN_TRACE_SQL = """
            INSERT INTO conversation_turn_traces (
                id, session_id, turn_number, channel, trace_id, parent_trace_id,
                started_at, ended_at, outcome, aborted,
                normalize_ms, guard_ms, routing_ms, llm_ttft_ms, llm_total_ms, rag_ms, tool_ms,
                guard_suspicious, guard_category, guard_implementation, guard_fallback,
                language, intent, intent_signals, routing_strategy, tier, routing_reason, semantic_margin,
                provider, model, prompt_tokens, completion_tokens,
                rag_cache_hit, rag_docs, tools,
                procedure_type, procedure_status, procedure_outcome_code,
                error_code, decision
            ) VALUES (
                ?,?,?,?,?,?,
                ?,?,?,?,
                ?,?,?,?,?,?,?,
                ?,?,?,?,
                ?,?,?,?,?,?,?,
                ?,?,?,?,
                ?,?::jsonb,?::jsonb,
                ?,?,?,
                ?,?::jsonb
            )
            ON CONFLICT (session_id, turn_number) DO NOTHING
            """;

    private final AuditEventBus bus;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Counter droppedWriteError;
    private final int batchSize;
    private final ScheduledExecutorService executor;
    private final Object drainLock = new Object();

    /** sessionId -> conversation_sessions.id. Single writer thread: no races. */
    private final ConcurrentHashMap<String, UUID> sessionIdCache = new ConcurrentHashMap<>();
    /** room -> voice_call_sessions.id. Single writer thread: no races. */
    private final ConcurrentHashMap<String, UUID> roomCallIdCache = new ConcurrentHashMap<>();

    public AuditBatchWriter(
            AuditEventBus bus,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MeterRegistry registry,
            @Value("${voxticket.audit.batch-size:200}") int batchSize,
            @Value("${voxticket.audit.flush-interval-ms:250}") long flushIntervalMs) {
        this.bus = bus;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, batchSize);
        this.droppedWriteError = Counter.builder("voxticket.telemetry.dropped")
                .tag("reason", "write_error")
                .description("Audit/telemetry events dropped because the batch write failed")
                .register(registry);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-writer");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(50, flushIntervalMs);
        this.executor.scheduleWithFixedDelay(() -> {
            try {
                drain();
            } catch (Exception e) {
                // drain() already contains its own catch-all; this is unreachable in practice.
                log.warn("event=audit_drain_unexpected_error errorType={}", e.getClass().getSimpleName());
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers this writer with the bus so {@link AuditEventBus#flush()}
     * can drain through it. Registration (not injection) breaks what would
     * otherwise be a bus &lt;-&gt; writer bean-creation cycle.
     */
    @PostConstruct
    void registerWithBus() {
        bus.registerWriter(this);
    }

    /**
     * Drains up to one batch from the bus and persists it. Synchronized so a
     * manual {@link AuditEventBus#flush()} can never interleave with the
     * scheduled drain. Never throws.
     */
    public void drain() {
        synchronized (drainLock) {
            List<AuditEvent> batch = new ArrayList<>(batchSize);
            bus.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                return;
            }
            try {
                writeBatch(batch);
            } catch (Exception e) {
                droppedWriteError.increment(batch.size());
                // Deliberately no stack trace: with a dead telemetry DB this
                // fires every flush interval and would drown the logs.
                log.warn("event=audit_batch_write_failed count={} errorType={}",
                        batch.size(), e.getClass().getSimpleName());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Persists one drained batch. Session upserts run first so every child
     * row can resolve its session UUID. Package-private for tests.
     */
    void writeBatch(List<AuditEvent> batch) {
        List<AuditEvent.MessageRecord> messages = new ArrayList<>();
        List<AuditEvent.EventRecord> events = new ArrayList<>();
        List<AuditEvent.TurnTraceRecord> traces = new ArrayList<>();
        List<AuditEvent.VoiceCallRecord> calls = new ArrayList<>();
        List<AuditEvent.VoiceTurnMetricRecord> turnMetrics = new ArrayList<>();
        List<AuditEvent.WorkerHeartbeat> heartbeats = new ArrayList<>();

        for (AuditEvent event : batch) {
            if (event instanceof AuditEvent.SessionTouch touch) {
                upsertSession(touch);
            } else if (event instanceof AuditEvent.MessageRecord m) {
                messages.add(m);
            } else if (event instanceof AuditEvent.EventRecord e) {
                events.add(e);
            } else if (event instanceof AuditEvent.TurnTraceRecord t) {
                traces.add(t);
            } else if (event instanceof AuditEvent.VoiceCallRecord c) {
                calls.add(c);
            } else if (event instanceof AuditEvent.VoiceTurnMetricRecord tm) {
                turnMetrics.add(tm);
            } else if (event instanceof AuditEvent.WorkerHeartbeat h) {
                heartbeats.add(h);
            } else {
                log.warn("event=audit_unknown_event_type type={}", event.getClass().getSimpleName());
                droppedWriteError.increment();
            }
        }

        if (!messages.isEmpty()) {
            insertMessages(messages);
        }
        if (!events.isEmpty()) {
            insertEvents(events);
        }
        for (AuditEvent.TurnTraceRecord trace : traces) {
            insertTurnTrace(trace);
        }
        for (AuditEvent.VoiceCallRecord call : calls) {
            writeVoiceCall(call);
        }
        for (AuditEvent.VoiceTurnMetricRecord tm : turnMetrics) {
            writeVoiceTurnMetric(tm);
        }
        for (AuditEvent.WorkerHeartbeat heartbeat : heartbeats) {
            writeHeartbeat(heartbeat);
        }
    }

    private void upsertSession(AuditEvent.SessionTouch touch) {
        UUID id = sessionIdCache.get(touch.sessionId());
        if (id == null) {
            UUID fresh = UUID.randomUUID();
            jdbc.update(UPSERT_SESSION_SQL,
                    fresh, touch.sessionId(), touch.channel().name(), touch.customerId(), touch.assurance(),
                    touch.escalated(), ts(touch.occurredAt()), ts(touch.occurredAt()),
                    touch.turnCount(), touch.lastTurnOutcome());
            id = queryUuidOrNull("SELECT id FROM conversation_sessions WHERE session_id = ?", touch.sessionId());
            if (id == null) {
                throw new IllegalStateException("session row missing after upsert: " + touch.sessionId());
            }
            sessionIdCache.put(touch.sessionId(), id);
        } else {
            jdbc.update(UPSERT_SESSION_SQL,
                    id, touch.sessionId(), touch.channel().name(), touch.customerId(), touch.assurance(),
                    touch.escalated(), ts(touch.occurredAt()), ts(touch.occurredAt()),
                    touch.turnCount(), touch.lastTurnOutcome());
        }
    }

    /**
     * Resolves the session UUID for a non-touch event. In production a
     * SessionTouch is always published before any message/event for the
     * session (ConversationRuntime touches first), so this is a cache hit.
     * The placeholder insert below only exists for the defensive case of an
     * event arriving with no touch in queue order; the real touch upsert
     * corrects customer/assurance/escalation when it lands.
     */
    private UUID requireSessionUuid(String sessionId) {
        UUID id = sessionIdCache.get(sessionId);
        if (id != null) {
            return id;
        }
        id = queryUuidOrNull("SELECT id FROM conversation_sessions WHERE session_id = ?", sessionId);
        if (id == null) {
            UUID fresh = UUID.randomUUID();
            jdbc.update("INSERT INTO conversation_sessions (id, session_id, channel, identity_assurance, started_at, last_activity_at)"
                            + " VALUES (?, ?, 'CHAT', 'ANONYMOUS', now(), now()) ON CONFLICT (session_id) DO NOTHING",
                    fresh, sessionId);
            id = queryUuidOrNull("SELECT id FROM conversation_sessions WHERE session_id = ?", sessionId);
            if (id == null) {
                throw new IllegalStateException("cannot resolve session row for: " + sessionId);
            }
        }
        sessionIdCache.put(sessionId, id);
        return id;
    }

    private void insertMessages(List<AuditEvent.MessageRecord> messages) {
        jdbc.batchUpdate(
                "INSERT INTO conversation_messages (id, session_id, turn_number, role, text, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                messages, batchSize,
                (PreparedStatement ps, AuditEvent.MessageRecord m) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, requireSessionUuid(m.sessionId()));
                    ps.setInt(3, m.turnNumber());
                    ps.setString(4, m.role());
                    ps.setString(5, m.text());
                    ps.setTimestamp(6, ts(m.occurredAt()));
                });
    }

    private void insertEvents(List<AuditEvent.EventRecord> events) {
        jdbc.batchUpdate(
                "INSERT INTO conversation_events (id, session_id, turn_number, event_type, detail, created_at, trace_id)"
                        + " VALUES (?,?,?,?,?,?,?)",
                events, batchSize,
                (PreparedStatement ps, AuditEvent.EventRecord e) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, requireSessionUuid(e.sessionId()));
                    if (e.turnNumber() == null) {
                        ps.setNull(3, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(3, e.turnNumber());
                    }
                    ps.setString(4, e.eventType());
                    ps.setString(5, e.detail());
                    ps.setTimestamp(6, ts(e.occurredAt()));
                    ps.setString(7, e.traceId());
                });
    }

    private void insertTurnTrace(AuditEvent.TurnTraceRecord record) {
        var t = record.trace();
        UUID sessionUuid = requireSessionUuid(t.sessionId());
        jdbc.update(INSERT_TURN_TRACE_SQL, (Object[]) new Object[]{
                UUID.randomUUID(), sessionUuid, t.turnNumber(), t.channel(), t.traceId(), t.parentTraceId(),
                ts(t.startedAt()), tsOrNull(t.endedAt()), t.outcome(), t.aborted(),
                t.normalizeMs(), t.guardMs(), t.routingMs(), t.llmTtftMs(), t.llmTotalMs(), t.ragMs(), t.toolMs(),
                t.guardSuspicious(), t.guardCategory(), t.guardImplementation(), t.guardFallback(),
                t.language(), t.intent(), t.intentSignals(), t.routingStrategy(), t.tier(), t.routingReason(), t.semanticMargin(),
                t.provider(), t.model(), t.promptTokens(), t.completionTokens(),
                t.ragCacheHit(), toJson(t.ragDocs()), toJson(t.tools()),
                t.procedureType(), t.procedureStatus(), t.procedureOutcomeCode(),
                t.errorCode(), toJson(t.decision()),
        });
    }

    private void writeVoiceCall(AuditEvent.VoiceCallRecord call) {
        UUID sessionUuid = sessionIdCache.get(call.sessionId());
        if (sessionUuid == null) {
            sessionUuid = queryUuidOrNull("SELECT id FROM conversation_sessions WHERE session_id = ?", call.sessionId());
            if (sessionUuid != null) {
                sessionIdCache.put(call.sessionId(), sessionUuid);
            }
        }
        if (sessionUuid == null) {
            // The call's conversation never touched the backend: nothing to link to.
            log.warn("event=audit_voice_call_skipped reason=unknown_session room={}", call.room());
            droppedWriteError.increment();
            return;
        }
        jdbc.update("""
                        INSERT INTO voice_call_sessions
                            (id, session_id, room, trace_id, stt_provider, stt_model, tts_provider, tts_model,
                             started_at, ended_at, outcome, barge_in_count, disconnect_reason, worker_id)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT (room) DO UPDATE SET
                            ended_at = EXCLUDED.ended_at,
                            outcome = EXCLUDED.outcome,
                            barge_in_count = EXCLUDED.barge_in_count,
                            disconnect_reason = EXCLUDED.disconnect_reason,
                            trace_id = COALESCE(EXCLUDED.trace_id, voice_call_sessions.trace_id),
                            worker_id = COALESCE(EXCLUDED.worker_id, voice_call_sessions.worker_id),
                            stt_provider = COALESCE(EXCLUDED.stt_provider, voice_call_sessions.stt_provider),
                            stt_model = COALESCE(EXCLUDED.stt_model, voice_call_sessions.stt_model),
                            tts_provider = COALESCE(EXCLUDED.tts_provider, voice_call_sessions.tts_provider),
                            tts_model = COALESCE(EXCLUDED.tts_model, voice_call_sessions.tts_model)
                        """,
                UUID.randomUUID(), sessionUuid, call.room(), call.traceId(),
                call.sttProvider(), call.sttModel(), call.ttsProvider(), call.ttsModel(),
                ts(call.startedAt()), tsOrNull(call.endedAt()), call.outcome(),
                call.bargeInCount(), call.disconnectReason(), call.workerId());
        UUID callId = queryUuidOrNull("SELECT id FROM voice_call_sessions WHERE room = ?", call.room());
        if (callId != null) {
            roomCallIdCache.put(call.room(), callId);
        }
    }

    private void writeVoiceTurnMetric(AuditEvent.VoiceTurnMetricRecord metric) {
        UUID callId = roomCallIdCache.get(metric.room());
        if (callId == null) {
            callId = queryUuidOrNull("SELECT id FROM voice_call_sessions WHERE room = ?", metric.room());
            if (callId == null) {
                log.warn("event=audit_voice_turn_metric_skipped reason=unknown_call room={}", metric.room());
                droppedWriteError.increment();
                return;
            }
            roomCallIdCache.put(metric.room(), callId);
        }
        jdbc.update("INSERT INTO voice_call_turn_metrics"
                        + " (id, call_id, turn_number, trace_id, stt_latency_ms, brain_ttft_ms, tts_first_audio_ms,"
                        + " e2e_ms, aborted, barge_in, stt_language, error)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (call_id, turn_number) DO NOTHING",
                UUID.randomUUID(), callId, metric.turnNumber(), metric.traceId(),
                metric.sttLatencyMs(), metric.brainTtftMs(), metric.ttsFirstAudioMs(),
                metric.e2eMs(), metric.aborted(), metric.bargeIn(), metric.sttLanguage(), metric.error());
        // The per-turn table carries no provider columns (schema design); fold
        // them into the parent call row when the worker reports them.
        if (metric.sttProvider() != null || metric.sttModel() != null
                || metric.ttsProvider() != null || metric.ttsModel() != null) {
            jdbc.update("UPDATE voice_call_sessions SET"
                            + " stt_provider = COALESCE(?, stt_provider),"
                            + " stt_model = COALESCE(?, stt_model),"
                            + " tts_provider = COALESCE(?, tts_provider),"
                            + " tts_model = COALESCE(?, tts_model) WHERE id = ?",
                    metric.sttProvider(), metric.sttModel(), metric.ttsProvider(), metric.ttsModel(), callId);
        }
    }

    private void writeHeartbeat(AuditEvent.WorkerHeartbeat heartbeat) {
        jdbc.update("INSERT INTO voice_worker_heartbeats (worker_id, stt_provider, tts_provider, active_rooms, last_seen_at)"
                        + " VALUES (?,?,?,?,?)"
                        + " ON CONFLICT (worker_id) DO UPDATE SET"
                        + " stt_provider = EXCLUDED.stt_provider,"
                        + " tts_provider = EXCLUDED.tts_provider,"
                        + " active_rooms = EXCLUDED.active_rooms,"
                        + " last_seen_at = EXCLUDED.last_seen_at",
                heartbeat.workerId(), heartbeat.sttProvider(), heartbeat.ttsProvider(),
                heartbeat.activeRooms(), ts(heartbeat.seenAt()));
    }

    private UUID queryUuidOrNull(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, UUID.class, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** Serializes a trace sub-structure to JSON. Never throws: failure stores NULL, never fails the batch. */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("event=audit_json_serialize_failed errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Timestamp tsOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** Stops the writer thread; best-effort final drain so shutdown doesn't silently drop queued telemetry. */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            drain();
        } catch (Exception ignored) {
            // Best effort only.
        }
    }
}
