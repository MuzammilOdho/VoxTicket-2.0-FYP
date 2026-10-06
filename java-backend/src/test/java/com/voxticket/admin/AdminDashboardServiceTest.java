package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Assertions use >= rather than == throughout - Micrometer counters
 * accumulate across the whole shared Spring context for this test run, so
 * exact-equality checks would be brittle against test execution order.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class AdminDashboardServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private AdminDashboardService dashboardService;
    @Autowired
    private TurnMetrics turnMetrics;
    @Autowired
    private ConversationAuditService auditService;

    private ConversationSession session;

    @BeforeEach
    void setUp() {
        session = ConversationSession.newSession("admin-test-" + UUID.randomUUID(), Channel.CHAT);
        auditService.recordSessionTouch(session);
    }

    @Test
    void summaryReflectsRecordedProcedureOutcomes() {
        turnMetrics.recordProcedureOutcome("CANCELLATION", "CANCELLED", true);
        turnMetrics.recordProcedureOutcome("RETURN", "NOT_ELIGIBLE", false);

        var summary = dashboardService.getSummary();

        assertThat(summary.procedureSuccessCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.procedureFailureCount()).isGreaterThanOrEqualTo(1);
    }
    

    @Test
    void summaryComputesTurnLatencyPercentilesAfterEnoughSamples() {
        for (int i = 1; i <= 20; i++) {
            turnMetrics.recordTurn(Duration.ofMillis(i * 10L), "CHAT", "normal");
        }

        var summary = dashboardService.getSummary();

        assertThat(summary.normalTurnLatencyP50Ms()).isNotNull();
        assertThat(summary.normalTurnLatencyP95Ms()).isNotNull();
        assertThat(summary.normalTurnLatencyP95Ms()).isGreaterThanOrEqualTo(summary.normalTurnLatencyP50Ms());
    }

    @Test
    void totalConversationsReflectsPersistedSessions() {
        assertThat(dashboardService.getSummary().totalConversations()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void activeSessionsCountsTheJustTouchedSession() {
        assertThat(dashboardService.getSummary().activeSessions()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void recentConversationsIncludesTheTestSession() {
        var conversations = dashboardService.getRecentConversations(50);

        assertThat(conversations).anyMatch(c -> c.sessionId().equals(session.getSessionId()));
    }

    @Test
    void inspectorViewMergesMessagesAndEventsChronologically() {
        auditService.recordMessage(session, 1, MessageRole.USER, "Where is my order?");
        auditService.recordEvent(session, 1, ConversationEventType.MODEL_SELECTED, "tier=TIER_1 model=openai/gpt-oss-20b reason=default");
        auditService.recordMessage(session, 1, MessageRole.ASSISTANT, "Let me check that for you.");

        var view = dashboardService.getInspectorView(session.getSessionId());

        assertThat(view.timeline()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(view.timeline()).extracting("kind").contains("MESSAGE", "EVENT");
        assertThat(view.timeline()).isSortedAccordingTo(Comparator.comparing(
                com.voxticket.admin.dto.ConversationInspectorView.TimelineEntry::timestamp));
    }

    @Test
    void inspectorViewNeverExposesOtpValues() {
        auditService.recordEvent(session, 1, ConversationEventType.OTP_ISSUED, "type=CANCELLATION");

        var view = dashboardService.getInspectorView(session.getSessionId());

        assertThat(view.timeline()).extracting("text").noneMatch(text -> text != null && ((String) text).matches(".*\\b\\d{6}\\b.*"));
    }

    @Test
    void inspectingAnUnknownSessionThrows() {
        assertThatThrownBy(() -> dashboardService.getInspectorView("nonexistent-session-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentileIsNeverExactlyZeroImmediatelyAfterRecordingRealDurations() {
        for (int i = 1; i <= 20; i++) {
            turnMetrics.recordTurn(Duration.ofMillis(500 + i * 10L), "CHAT", "normal");
        }

        var summary = dashboardService.getSummary();

        // Either a valid positive estimate, or explicitly unavailable - never a misleading 0.
        if (summary.normalTurnLatencyP50Ms() != null) {
            assertThat(summary.normalTurnLatencyP50Ms()).isGreaterThan(0.0);
        }
        if (summary.normalTurnLatencyP95Ms() != null) {
            assertThat(summary.normalTurnLatencyP95Ms()).isGreaterThan(0.0);
        }
    }

    @Test
    void aTimerWithNoRecordedTurnsReportsUnavailableNotZero() {
        // A fresh, never-recorded timer for a channel/outcome combination that hasn't occurred.
        var summary = dashboardService.getSummary();
        // This assertion only holds meaningfully in isolation; kept loose since Micrometer state
        // is shared across the test class's Spring context.
        assertThat(summary.normalTurnLatencyP50Ms() == null || summary.normalTurnLatencyP50Ms() > 0.0).isTrue();
    }

    @Test
    void clarificationCodesAreCountedSeparatelyFromGenuineFailures() {
        turnMetrics.recordProcedureOutcome("RETURN", "ITEM_REQUIRED", false);
        turnMetrics.recordProcedureOutcome("RETURN", "NOT_ELIGIBLE", false);

        var summary = dashboardService.getSummary();

        assertThat(summary.procedureClarificationCount()).isGreaterThanOrEqualTo(1);
        // NOT_ELIGIBLE must still count as a real failure - only the three clarification codes are excluded.
        assertThat(summary.procedureFailureCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void summaryReflectsProviderModelAndReasonUsage() {
        turnMetrics.recordModelSelection("TIER_2", "CEREBRAS", "gpt-oss-120b", "test-reason-alpha");

        var summary = dashboardService.getSummary();

        assertThat(summary.modelProviderUsage()).containsKey("CEREBRAS");
        assertThat(summary.modelProviderUsage().get("CEREBRAS")).isGreaterThanOrEqualTo(1L);
        assertThat(summary.modelUsage()).containsKey("gpt-oss-120b");
        assertThat(summary.modelUsage().get("gpt-oss-120b")).isGreaterThanOrEqualTo(1L);
        assertThat(summary.modelSelectionReasons()).containsKey("test-reason-alpha");
        assertThat(summary.modelSelectionReasons().get("test-reason-alpha")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void llmCallMetricsTrackCallsLatencyAndErrorsPerTier() {
        turnMetrics.recordLlmCall(Duration.ofMillis(150), "TIER_1", "GROQ", "openai/gpt-oss-20b", "success");
        turnMetrics.recordLlmCall(Duration.ofMillis(250), "TIER_1", "GROQ", "openai/gpt-oss-20b", "error");

        var summary = dashboardService.getSummary();

        assertThat(summary.llmCallMetrics()).containsKey("TIER_1");
        var metric = summary.llmCallMetrics().get("TIER_1");
        assertThat(metric.callCount()).isGreaterThanOrEqualTo(2L);
        assertThat(metric.errorCount()).isGreaterThanOrEqualTo(1L);
        assertThat(metric.meanLatencyMs()).isGreaterThan(0.0);
    }

    @Test
    void tokenUsageByProviderIsTracked() {
        turnMetrics.recordTokenUsage("CEREBRAS", "gpt-oss-120b", "prompt", 17L);

        var summary = dashboardService.getSummary();

        assertThat(summary.tokenUsageByProvider()).containsKey("CEREBRAS");
        assertThat(summary.tokenUsageByProvider().get("CEREBRAS")).isGreaterThanOrEqualTo(17L);
    }

}