package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.admin.dto.VoiceAnalyticsSummaryDtos.VoiceAnalyticsSummaryDto;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.TurnTraceRecordRepository;
import com.voxticket.persistence.repository.VoiceCallSessionRepository;
import com.voxticket.persistence.repository.VoiceCallTurnMetricRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * P4.3: aggregate rate math over an in-memory fixture dataset. The
 * repositories are mocked (their JPQL is exercised against the real
 * database by the testcontainers suite); these tests pin the service's
 * rate definitions, zero-division safety, and window clamping. Plain
 * unit tests - no Spring context, no database.
 */
class VoiceAggregateAnalyticsServiceTest {

    private VoiceCallSessionRepository callRepository;
    private TurnTraceRecordRepository turnTraceRepository;
    private VoiceCallTurnMetricRepository turnMetricRepository;
    private ConversationEventRecordRepository eventRepository;
    private VoiceAggregateAnalyticsService service;

    @BeforeEach
    void setUp() {
        callRepository = mock(VoiceCallSessionRepository.class);
        turnTraceRepository = mock(TurnTraceRecordRepository.class);
        turnMetricRepository = mock(VoiceCallTurnMetricRepository.class);
        eventRepository = mock(ConversationEventRecordRepository.class);
        service = new VoiceAggregateAnalyticsService(
                callRepository, turnTraceRepository, turnMetricRepository, eventRepository);
    }

    @Test
    void summaryComputesRatesFromFixtureCounts() {
        when(callRepository.countByStartedAtGreaterThanEqualAndStartedAtLessThan(any(Instant.class), any(Instant.class)))
                .thenReturn(10L);
        when(callRepository.countCompletedInWindow(any(Instant.class), any(Instant.class))).thenReturn(7L);
        when(turnTraceRepository.countVoiceTurns(any(Instant.class), any(Instant.class))).thenReturn(40L);
        when(turnTraceRepository.countSupersededTurns(any(Instant.class), any(Instant.class))).thenReturn(4L);
        when(turnMetricRepository.countInWindow(any(Instant.class), any(Instant.class))).thenReturn(40L);
        when(turnMetricRepository.countStalledInWindow(any(Instant.class), any(Instant.class))).thenReturn(2L);
        when(eventRepository.countByTypeInWindow(eq(ConversationEventType.PROCEDURE_CLARIFICATION),
                any(Instant.class), any(Instant.class))).thenReturn(6L);
        when(eventRepository.countByTypeInWindow(eq(ConversationEventType.ESCALATED),
                any(Instant.class), any(Instant.class))).thenReturn(1L);
        when(turnTraceRepository.turnCountsPerSession(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new Object[]{"s-1", 10L}, new Object[]{"s-2", 30L}));
        when(turnTraceRepository.topIntents(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(new Object[]{"cancel_order", 12L}, new Object[]{"track_order", 8L}));

        VoiceAnalyticsSummaryDto summary = service.summary(7);

        assertThat(summary.windowDays()).isEqualTo(7);
        assertThat(summary.callCount()).isEqualTo(10L);
        assertThat(summary.completionRate()).isEqualTo(0.7);
        assertThat(summary.bargeInRate()).isEqualTo(0.1);
        assertThat(summary.stallRate()).isEqualTo(0.05);
        assertThat(summary.clarificationRate()).isEqualTo(0.15);
        assertThat(summary.handoffOfferRate()).isEqualTo(0.1);
        assertThat(summary.avgTurnCount()).isEqualTo(20.0);
        assertThat(summary.topIntents()).hasSize(2);
        assertThat(summary.topIntents().get(0).intent()).isEqualTo("cancel_order");
        assertThat(summary.topIntents().get(0).count()).isEqualTo(12L);
    }

    @Test
    void emptyWindowYieldsZerosNotNaN() {
        VoiceAnalyticsSummaryDto summary = service.summary(7);

        assertThat(summary.callCount()).isZero();
        assertThat(summary.completionRate()).isZero();
        assertThat(summary.bargeInRate()).isZero();
        assertThat(summary.stallRate()).isZero();
        assertThat(summary.clarificationRate()).isZero();
        assertThat(summary.handoffOfferRate()).isZero();
        assertThat(summary.avgTurnCount()).isZero();
        assertThat(summary.topIntents()).isEmpty();
    }

    @Test
    void invalidWindowFallsBackToSevenDays() {
        assertThat(service.summary(0).windowDays()).isEqualTo(7);
        assertThat(service.summary(-3).windowDays()).isEqualTo(7);
        assertThat(service.summary(1000).windowDays()).isEqualTo(7);
        assertThat(service.summary(30).windowDays()).isEqualTo(30);
    }
}
