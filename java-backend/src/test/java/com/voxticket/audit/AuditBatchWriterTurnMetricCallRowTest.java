package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Latency Step 0: a turn metric arriving before the worker's call record
 * must create its call row instead of being dropped as unknown_call.
 *
 * <p>Uses a {@link JdbcTemplate} mock, so no database is needed. The mock
 * answers lookups by SQL text: the voice_call_sessions lookup returns
 * null on the first call (no call row yet) and the created call id after
 * the insert; the conversation_sessions lookup returns the session id.
 * Written SQL is recorded (not verified with varargs matchers) so the
 * assertions are plain list checks.
 */
class AuditBatchWriterTurnMetricCallRowTest {

    private SimpleMeterRegistry registry;
    private AuditEventBus bus;
    private AuditBatchWriter writer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bus = new AuditEventBus(64, registry);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void turnMetricBeforeCallRecordCreatesCallRowInsteadOfDropping() {
        UUID sessionId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> writtenSql = new ArrayList<>();
        doAnswer(invocation -> {
            writtenSql.add(invocation.getArgument(0));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        AtomicInteger callRowLookups = new AtomicInteger();
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer((Answer<UUID>) invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("voice_call_sessions")) {
                        // First lookup: no call row yet. Later lookups (after
                        // the insert): the row exists.
                        return callRowLookups.incrementAndGet() == 1 ? null : callId;
                    }
                    if (sql.contains("conversation_sessions")) {
                        return sessionId;
                    }
                    return null;
                });
        // Long flush interval: the background thread never fires mid-test; drain() is called explicitly.
        writer = new AuditBatchWriter(bus, jdbc, new ObjectMapper(), registry, 200, 3_600_000L);
        bus.registerWriter(writer);

        bus.publish(new AuditEvent.VoiceTurnMetricRecord(
                "demo-test-room", 1, "trace-1",
                100.0, 2000.0, 300.0, 2500.0,
                false, false, "en",
                "assemblyai", "assemblyai-model", "cartesia", "cartesia-voice",
                null, Instant.now()));

        writer.drain();

        // The metric must have been written, not dropped: a call row insert
        // and a turn metric insert, and no dropped-write increment.
        assertThat(writtenSql).anyMatch(sql -> sql.contains("INSERT INTO voice_call_sessions"));
        assertThat(writtenSql).anyMatch(sql -> sql.contains("INSERT INTO voice_call_turn_metrics"));
        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "write_error").count())
                .isZero();
        assertThat(bus.depth()).isZero();
    }

    @Test
    void turnMetricWithNoConversationRowIsStillDropped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> writtenSql = new ArrayList<>();
        doAnswer(invocation -> {
            writtenSql.add(invocation.getArgument(0));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(null);
        writer = new AuditBatchWriter(bus, jdbc, new ObjectMapper(), registry, 200, 3_600_000L);
        bus.registerWriter(writer);

        bus.publish(new AuditEvent.VoiceTurnMetricRecord(
                "demo-unknown-room", 1, "trace-1",
                100.0, 2000.0, 300.0, 2500.0,
                false, false, "en",
                null, null, null, null,
                null, Instant.now()));

        writer.drain();

        // No conversation row means no session FK: nothing can be linked,
        // so no turn metric insert, and the drop is counted once.
        assertThat(writtenSql).noneMatch(sql -> sql.contains("INSERT INTO voice_call_turn_metrics"));
        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "write_error").count())
                .isEqualTo(1.0);
        assertThat(bus.depth()).isZero();
    }
}
