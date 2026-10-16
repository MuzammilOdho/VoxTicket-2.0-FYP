package com.voxticket.admin;

import com.voxticket.admin.dto.VoiceAnalyticsSummaryDtos.TopIntentDto;
import com.voxticket.admin.dto.VoiceAnalyticsSummaryDtos.VoiceAnalyticsSummaryDto;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.TurnTraceRecordRepository;
import com.voxticket.persistence.repository.VoiceCallSessionRepository;
import com.voxticket.persistence.repository.VoiceCallTurnMetricRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4.3: aggregate voice analytics over the existing turn-trace, audit
 * event, and voice telemetry tables.
 *
 * <p>Read-only: every number comes from a JPQL count/group query (see the
 * repository interfaces), and every rate is guarded against an empty
 * denominator (0.0, never NaN). Nothing is estimated or fabricated: a
 * missing data source shows as 0.0, which is honest for a young table.
 *
 * <p>Rate definitions:
 * <ul>
 *   <li><b>completionRate</b> - calls the worker reported {@code completed}
 *       / calls started in the window.</li>
 *   <li><b>bargeInRate</b> - turns aborted as {@code superseded} / voice
 *       turns in the window.</li>
 *   <li><b>stallRate</b> - P1.4 stalled turn metrics / voice turn metrics
 *       in the window.</li>
 *   <li><b>clarificationRate</b> - P2.4 {@code PROCEDURE_CLARIFICATION}
 *       events / voice turns in the window.</li>
 *   <li><b>handoffOfferRate</b> - {@code ESCALATED} events / calls started
 *       in the window.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class VoiceAggregateAnalyticsService {

    private static final int TOP_INTENT_LIMIT = 5;
    private static final int MAX_WINDOW_DAYS = 90;

    private final VoiceCallSessionRepository callRepository;
    private final TurnTraceRecordRepository turnTraceRepository;
    private final VoiceCallTurnMetricRepository turnMetricRepository;
    private final ConversationEventRecordRepository eventRepository;

    public VoiceAggregateAnalyticsService(
            VoiceCallSessionRepository callRepository,
            TurnTraceRecordRepository turnTraceRepository,
            VoiceCallTurnMetricRepository turnMetricRepository,
            ConversationEventRecordRepository eventRepository) {
        this.callRepository = callRepository;
        this.turnTraceRepository = turnTraceRepository;
        this.turnMetricRepository = turnMetricRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Aggregate summary over the trailing {@code days} (clamped to
     * 1..90; invalid values fall back to 7).
     */
    public VoiceAnalyticsSummaryDto summary(int days) {
        int windowDays = (days < 1 || days > MAX_WINDOW_DAYS) ? 7 : days;
        Instant to = Instant.now();
        Instant from = to.minusSeconds((long) windowDays * 24 * 60 * 60);

        long callCount = callRepository.countByStartedAtGreaterThanEqualAndStartedAtLessThan(from, to);
        long completedCalls = callRepository.countCompletedInWindow(from, to);

        long voiceTurns = turnTraceRepository.countVoiceTurns(from, to);
        long supersededTurns = turnTraceRepository.countSupersededTurns(from, to);

        long turnMetrics = turnMetricRepository.countInWindow(from, to);
        long stalledTurns = turnMetricRepository.countStalledInWindow(from, to);

        long clarifications = eventRepository.countByTypeInWindow(
                ConversationEventType.PROCEDURE_CLARIFICATION, from, to);
        long handoffs = eventRepository.countByTypeInWindow(
                ConversationEventType.ESCALATED, from, to);

        double avgTurnCount = averageTurnCount(from, to);

        List<TopIntentDto> topIntents = turnTraceRepository
                .topIntents(from, to, PageRequest.of(0, TOP_INTENT_LIMIT)).stream()
                .map(row -> new TopIntentDto((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        return new VoiceAnalyticsSummaryDto(
                windowDays,
                callCount,
                rate(completedCalls, callCount),
                rate(supersededTurns, voiceTurns),
                rate(stalledTurns, turnMetrics),
                rate(clarifications, voiceTurns),
                rate(handoffs, callCount),
                avgTurnCount,
                topIntents);
    }

    private double averageTurnCount(Instant from, Instant to) {
        List<Object[]> perSession = turnTraceRepository.turnCountsPerSession(from, to);
        if (perSession.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Object[] row : perSession) {
            total += ((Number) row[1]).doubleValue();
        }
        return total / perSession.size();
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
