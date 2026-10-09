package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.voxticket.agent.AgentResponse;
import com.voxticket.agent.SupportAgent;
import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationLanguageResolver;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.DirectProcedureResponseRenderer;
import com.voxticket.conversation.InMemorySessionStore;
import com.voxticket.conversation.MessageRole;
import com.voxticket.conversation.UserTurn;
import com.voxticket.identity.IdentityService;
import com.voxticket.observability.TraceIds;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.procedure.ExplicitConfirmationParser;
import com.voxticket.procedure.ProcedureCoordinator;
import com.voxticket.safety.InputNormalizer;
import com.voxticket.safety.PromptGuard;
import com.voxticket.safety.PromptGuardVerdict;
import com.voxticket.verification.SensitiveTurnParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

/**
 * P1, THE key proof: if PostgreSQL telemetry persistence fails, the
 * conversation still completes normally.
 *
 * <p>Uses a {@link JdbcTemplate} mock that throws on every write path, so no
 * database is needed. Two levels:
 * <ol>
 *   <li>{@link AuditBatchWriter#drain()} with a dead DB: no exception escapes
 *       and the whole batch is counted in
 *       {@code voxticket.telemetry.dropped{reason=write_error}}.</li>
 *   <li>A full {@link ConversationRuntime#processTurn} with the audit service
 *       wired to the failing writer: the turn returns a normal
 *       {@link AssistantTurn} even though every telemetry write fails.</li>
 * </ol>
 */
class AuditBatchWriterFailureIsolationTest {

    private SimpleMeterRegistry registry;
    private AuditEventBus bus;
    private JdbcTemplate failingJdbc;
    private AuditBatchWriter writer;

    private static AuditEvent.SessionTouch touch(String sessionId) {
        return new AuditEvent.SessionTouch(sessionId, Channel.CHAT, null, "ANONYMOUS", false, 0, null,
                TraceIds.newTraceId(), Instant.now());
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bus = new AuditEventBus(64, registry);
        failingJdbc = mock(JdbcTemplate.class);
        RuntimeException dbDown = new RuntimeException("telemetry db down");
        doThrow(dbDown).when(failingJdbc).update(anyString(), any(Object[].class));
        doThrow(dbDown).when(failingJdbc).batchUpdate(anyString(), anyList(), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
        doThrow(dbDown).when(failingJdbc).queryForObject(anyString(), any(Class.class), any(Object[].class));
        // Long flush interval: the background thread never fires mid-test; drain() is called explicitly.
        writer = new AuditBatchWriter(bus, failingJdbc, new ObjectMapper(), registry, 200, 3_600_000L);
        bus.registerWriter(writer);
    }

    @AfterEach
    void tearDown() {
        writer.shutdown();
    }

    @Test
    void batchWriteFailureIsContainedAndCounted() {
        bus.publish(touch("s1"));
        bus.publish(new AuditEvent.MessageRecord("s1", 1, MessageRole.USER.name(), "hello",
                TraceIds.newTraceId(), Instant.now()));

        assertThatCode(() -> writer.drain()).doesNotThrowAnyException();

        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "write_error").count())
                .isEqualTo(2.0);
        assertThat(bus.depth()).isZero();
    }

    @Test
    void turnStillCompletesWhenTelemetryPersistenceFails() {
        ConversationAuditService auditService = new ConversationAuditService(bus);

        SupportAgent supportAgent = mock(SupportAgent.class);
        when(supportAgent.respond(any(ConversationSession.class), anyString()))
                .thenReturn(new AgentResponse("stubbed reply", AgentResponse.Outcome.SUCCESS));
        PromptGuard promptGuard = mock(PromptGuard.class);
        when(promptGuard.evaluate(anyString())).thenReturn(PromptGuardVerdict.allow());

        ConversationRuntime runtime = new ConversationRuntime(
                new InMemorySessionStore(),
                mock(IdentityService.class),
                supportAgent,
                new InputNormalizer(),
                promptGuard,
                mock(ExplicitConfirmationParser.class),
                mock(SensitiveTurnParser.class),
                mock(ProcedureCoordinator.class),
                new TurnMetrics(new SimpleMeterRegistry()),
                auditService,
                mock(ConversationLanguageResolver.class),
                mock(DirectProcedureResponseRenderer.class));

        // The turn itself must succeed even though every telemetry write fails.
        AssistantTurn result = runtime.processTurn(new UserTurn(
                "s-fail-" + UUID.randomUUID(), Channel.CHAT, "hello", null, Instant.now(), Map.of()));

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("stubbed reply");

        // Telemetry was attempted (5 events queued: session touch, user
        // message, assistant message, turn completion, turn decision trace)
        // and failed in isolation.
        assertThat(bus.depth()).isEqualTo(5);
        assertThatCode(() -> writer.drain()).doesNotThrowAnyException();
        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "write_error").count())
                .isEqualTo(5.0);
        assertThat(bus.depth()).isZero();
    }
}
