package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.admin.dto.ConversationDetailDto;
import com.voxticket.admin.dto.ConversationSummaryDto;
import com.voxticket.admin.dto.PageDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 12 (P4): conversation search filters, pagination, and the merged
 * detail timeline. Seeds V6/V9 rows directly; every session id is unique
 * per test method so the shared scratch database stays isolated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ConversationAdminServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ConversationAdminService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "conv-" + UUID.randomUUID();
    }

    private UUID insertSession(String sessionId, String channel, boolean escalated,
            Instant lastActivityAt, int turnCount, String lastTurnOutcome) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversation_sessions "
                        + "(id, session_id, channel, customer_id, identity_assurance, escalated, "
                        + "started_at, last_activity_at, turn_count, last_turn_outcome) "
                        + "VALUES (?, ?, ?, NULL, 'ANONYMOUS', ?, ?, ?, ?, ?)",
                id, sessionId, channel, escalated,
                java.sql.Timestamp.from(lastActivityAt.minusSeconds(3600)),
                java.sql.Timestamp.from(lastActivityAt), turnCount, lastTurnOutcome);
        return id;
    }

    private void insertTrace(UUID sessionRowId, int turnNumber, String channel,
            String traceId, String outcome, boolean aborted, Instant startedAt) {
        jdbc.update(
                "INSERT INTO conversation_turn_traces "
                        + "(session_id, turn_number, channel, trace_id, started_at, ended_at, outcome, aborted, "
                        + "tier, provider, model, prompt_tokens, completion_tokens) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TIER_1', 'GOOGLE', 'gemini-3.5-flash-lite', 10, 20)",
                sessionRowId, turnNumber, channel, traceId,
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(2)), outcome, aborted);
    }

    private void insertMessage(UUID sessionRowId, int turnNumber, String role, String text) {
        jdbc.update(
                "INSERT INTO conversation_messages (session_id, turn_number, role, text, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionRowId, turnNumber, role, text,
                java.sql.Timestamp.from(Instant.now()));
    }

    /** trace_id is VARCHAR(32); keep generated ids short and unique. */
    private static String shortTrace() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    void search_filtersByChannel() {
        insertSession(prefix + "-chat", "CHAT", false, Instant.now(), 2, "normal");
        insertSession(prefix + "-phone", "PHONE", false, Instant.now(), 1, "normal");

        PageDto<ConversationSummaryDto> chat = service.searchConversations("chat", null, null, prefix, null, null, 0, 20);
        assertThat(chat.content()).hasSize(1);
        assertThat(chat.content().get(0).channel()).isEqualTo("chat");
    }

    @Test
    void search_filtersByOutcomeAndDerivesAbortedStatus() {
        insertSession(prefix + "-aborted", "CHAT", false,
                Instant.now().minusSeconds(3600), 3, "aborted");
        insertSession(prefix + "-normal", "CHAT", false,
                Instant.now().minusSeconds(3600), 3, "normal");

        PageDto<ConversationSummaryDto> aborted =
                service.searchConversations(null, "aborted", null, prefix, null, null, 0, 20);
        assertThat(aborted.content()).hasSize(1);
        assertThat(aborted.content().get(0).status()).isEqualTo("ABORTED");

        PageDto<ConversationSummaryDto> normal =
                service.searchConversations(null, "normal", null, prefix, null, null, 0, 20);
        assertThat(normal.content()).hasSize(1);
        assertThat(normal.content().get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void search_activeTakesPrecedenceOverAborted() {
        insertSession(prefix + "-recent-aborted", "CHAT", false, Instant.now(), 1, "aborted");

        PageDto<ConversationSummaryDto> page =
                service.searchConversations(null, null, null, prefix, null, null, 0, 20);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void search_paginates() {
        for (int i = 0; i < 5; i++) {
            insertSession(prefix + "-p" + i, "CHAT", false,
                    Instant.now().minusSeconds(3600 + i), 1, "normal");
        }

        var first = service.searchConversations(null, null, null, prefix, null, null, 0, 2);
        var second = service.searchConversations(null, null, null, prefix, null, null, 1, 2);
        var third = service.searchConversations(null, null, null, prefix, null, null, 2, 2);
        assertThat(first.content()).hasSize(2);
        assertThat(second.content()).hasSize(2);
        assertThat(third.content()).hasSize(1);
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
    }

    @Test
    void detail_mergesMessagesAndTracesInTimeOrder() {
        UUID sessionRowId = insertSession(prefix + "-detail", "CHAT", true,
                Instant.now().minusSeconds(3600), 2, "normal");
        Instant t0 = Instant.now().minusSeconds(3000);
        String trace1 = shortTrace();
        String trace2 = shortTrace();
        insertMessage(sessionRowId, 1, "USER", "hello");
        insertMessage(sessionRowId, 1, "ASSISTANT", "hi there");
        insertTrace(sessionRowId, 1, "CHAT", trace1, "normal", false, t0);
        insertTrace(sessionRowId, 2, "CHAT", trace2, "blocked", false, t0.plusSeconds(60));

        ConversationDetailDto detail = service.getDetail(prefix + "-detail");
        assertThat(detail.session().sessionId()).isEqualTo(prefix + "-detail");
        assertThat(detail.session().escalated()).isTrue();
        assertThat(detail.session().turnCount()).isEqualTo(2);
        assertThat(detail.timeline()).hasSize(4);
        // Time-ordered: trace(turn 1), trace(turn 2), message, message.
        assertThat(detail.timeline().get(0).kind()).isEqualTo("TRACE");
        assertThat(detail.timeline().get(0).traceId()).isEqualTo(trace1);
        assertThat(detail.timeline().get(1).kind()).isEqualTo("TRACE");
        assertThat(detail.timeline().get(1).detail()).contains("outcome=blocked");
        assertThat(detail.timeline().get(2).kind()).isEqualTo("MESSAGE");
        assertThat(detail.timeline().get(3).kind()).isEqualTo("MESSAGE");
    }

    @Test
    void detail_unknownSession_throws() {
        assertThatThrownBy(() -> service.getDetail(prefix + "-missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
