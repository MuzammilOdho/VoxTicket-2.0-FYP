package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P1: the single writer thread drains one FIFO queue, so per-session events
 * are persisted in publish order. Every row's timestamp is the event's
 * occurrence time (not the flush time), so the admin timeline sorts by when
 * things happened.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
class AuditOrderingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ConversationAuditService auditService;
    @Autowired
    private AuditEventBus bus;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publishedEventsPersistInPublishOrderWithOccurrenceTimestamps() {
        String sessionId = "ordering-" + UUID.randomUUID();
        ConversationSession session = ConversationSession.newSession(sessionId, Channel.CHAT);

        auditService.recordSessionTouch(session);
        auditService.recordMessage(session, 1, MessageRole.USER, "first");
        auditService.recordEvent(session, 1, ConversationEventType.TOOL_CALLED, "tool=orderLookup");
        auditService.recordMessage(session, 1, MessageRole.ASSISTANT, "second");
        auditService.recordTurnCompletion(session, 1, "normal", false);

        bus.flush();

        List<String> texts = jdbc.query(
                "SELECT m.text FROM conversation_messages m JOIN conversation_sessions s ON s.id = m.session_id"
                        + " WHERE s.session_id = ? ORDER BY m.created_at",
                (rs, i) -> rs.getString(1), sessionId);
        assertThat(texts).containsExactlyInAnyOrder("first", "second");

        List<Instant> created = jdbc.query(
                "SELECT m.created_at FROM conversation_messages m JOIN conversation_sessions s ON s.id = m.session_id"
                        + " WHERE s.session_id = ? ORDER BY m.created_at",
                (rs, i) -> rs.getTimestamp(1).toInstant(), sessionId);
        assertThat(created).hasSize(2).isSorted();

        Integer turnCount = jdbc.queryForObject(
                "SELECT turn_count FROM conversation_sessions WHERE session_id = ?", Integer.class, sessionId);
        assertThat(turnCount).isEqualTo(1);
        String outcome = jdbc.queryForObject(
                "SELECT last_turn_outcome FROM conversation_sessions WHERE session_id = ?", String.class, sessionId);
        assertThat(outcome).isEqualTo("normal");

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT e.turn_number, e.event_type FROM conversation_events e"
                        + " JOIN conversation_sessions s ON s.id = e.session_id WHERE s.session_id = ?", sessionId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("turn_number")).isEqualTo(1);
        assertThat(events.get(0).get("event_type")).isEqualTo("TOOL_CALLED");
    }
}
