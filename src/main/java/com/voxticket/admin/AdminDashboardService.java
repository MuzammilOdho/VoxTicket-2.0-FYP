package com.voxticket.admin;

import com.voxticket.admin.dto.AdminDashboardSummary;
import com.voxticket.admin.dto.ConversationInspectorView;
import com.voxticket.admin.dto.ConversationSummaryView;
import com.voxticket.persistence.entity.ConversationEventRecord;
import com.voxticket.persistence.entity.ConversationMessageRecord;
import com.voxticket.persistence.entity.ConversationSessionRecord;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 9 (Admin Dashboard).
 *
 * Aggregate operational numbers come from Micrometer/TurnMetrics, which are
 * already tagged for this purpose but reset on restart.
 *
 * Conversation counts and per-session inspection come from the durable audit
 * tables because those need to survive application restarts.
 */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final ConversationSessionRecordRepository sessionRepository;
    private final ConversationMessageRecordRepository messageRepository;
    private final ConversationEventRecordRepository eventRepository;
    private final MeterRegistry meterRegistry;
    private final long activeSessionWindowMinutes;

    public AdminDashboardService(
            ConversationSessionRecordRepository sessionRepository,
            ConversationMessageRecordRepository messageRepository,
            ConversationEventRecordRepository eventRepository,
            MeterRegistry meterRegistry,
            @Value("${voxticket.admin.active-session-window-minutes:15}")
            long activeSessionWindowMinutes) {

        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.meterRegistry = meterRegistry;
        this.activeSessionWindowMinutes = activeSessionWindowMinutes;
    }

    private static final Set<String> CLARIFICATION_CODES = Set.of("ITEM_REQUIRED", "REASON_REQUIRED", "PROBLEM_REQUIRED");

    public AdminDashboardSummary getSummary() {
        long totalConversations = sessionRepository.count();
        Instant activeCutoff = Instant.now().minus(activeSessionWindowMinutes, ChronoUnit.MINUTES);
        long activeSessions = sessionRepository.countByLastActivityAtAfter(activeCutoff);

        long procedureSuccess = sumCountersWhereTag("voxticket.procedure.outcome", "success", "true");

        Map<String, Long> modelTierUsage = sumCountersByTag("voxticket.model.selection", "tier");
        Map<String, Long> modelProviderUsage = sumCountersByTag("voxticket.model.selection", "provider");
        Map<String, Long> modelUsage = sumCountersByTag("voxticket.model.selection", "model");
        Map<String, Long> modelSelectionReasons = sumCountersByTag("voxticket.model.selection", "reason");
        Map<String, Long> tokenUsage = sumCountersByTag("voxticket.llm.tokens", "type");
        Map<String, Long> tokenUsageByProvider = sumCountersByTag("voxticket.llm.tokens", "provider");
        Map<String, AdminDashboardSummary.LlmCallMetric> llmCallMetrics = computeLlmCallMetrics();


        long procedureFailure = sumCountersWhereTagExcludingCodes("voxticket.procedure.outcome", "success", "false", CLARIFICATION_CODES);
        long procedureClarification = sumCountersWhereCodeIn("voxticket.procedure.outcome", CLARIFICATION_CODES);
        long escalationCount = sumCountersWhereTagPair("voxticket.procedure.outcome", "type", "ESCALATION", "success", "true");

        Timer normalTurnTimer = findTimer("voxticket.turn.duration", Map.of("channel", "CHAT", "outcome", "normal"));
        Double p50 = extractReliablePercentile(normalTurnTimer, 0.5);
        Double p95 = extractReliablePercentile(normalTurnTimer, 0.95);
        long totalTurns = (long) meterRegistry.find("voxticket.turn.duration").timers().stream().mapToDouble(Timer::count).sum();

        Map<String, AdminDashboardSummary.ToolMetric> toolMetrics = computeToolMetrics();

        Timer ragTimer = findTimer("voxticket.rag.search.duration", Map.of());
        long ragCount = ragTimer == null ? 0 : ragTimer.count();
        double ragMeanMs = ragTimer == null ? 0.0 : ragTimer.mean(TimeUnit.MILLISECONDS);
        var ragRetrievedSummary = meterRegistry.find("voxticket.rag.retrieved.count").summary();
        double ragMeanRetrieved = ragRetrievedSummary == null ? 0.0 : ragRetrievedSummary.mean();

        return new AdminDashboardSummary(
                totalConversations, activeSessions, procedureSuccess, procedureFailure, procedureClarification, escalationCount,
                modelTierUsage, modelProviderUsage, modelUsage, modelSelectionReasons,
                tokenUsage, tokenUsageByProvider, llmCallMetrics,
                p50, p95, totalTurns, toolMetrics,
                new AdminDashboardSummary.RagMetric(ragCount, ragMeanMs, ragMeanRetrieved));
    }

        private long sumCountersWhereTagExcludingCodes(String meterName, String tagKey, String tagValue, Set<String> excludedCodes) {
        return (long) meterRegistry.find(meterName).meters().stream()
                .filter(meter -> tagValue.equals(meter.getId().getTag(tagKey)))
                .filter(meter -> !excludedCodes.contains(meter.getId().getTag("code")))
                .filter(Counter.class::isInstance)
                .mapToDouble(meter -> ((Counter) meter).count())
                .sum();
    }

    private long sumCountersWhereCodeIn(String meterName, Set<String> codes) {
        return (long) meterRegistry.find(meterName).meters().stream()
                .filter(meter -> codes.contains(meter.getId().getTag("code")))
                .filter(Counter.class::isInstance)
                .mapToDouble(meter -> ((Counter) meter).count())
                .sum();
    }
    public List<ConversationSummaryView> getRecentConversations(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));

        return sessionRepository
                .findAll(
                        PageRequest.of(
                                0,
                                safeLimit,
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "lastActivityAt")))
                .getContent()
                .stream()
                .map(this::toSummaryView)
                .toList();
    }

    public ConversationInspectorView getInspectorView(String sessionId) {
        ConversationSessionRecord session =
                sessionRepository
                        .findBySessionId(sessionId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No conversation found for session "
                                                        + sessionId));

        List<ConversationMessageRecord> messages =
                messageRepository
                        .findBySessionIdOrderByTurnNumberAsc(session.getId());

        List<ConversationEventRecord> events =
                eventRepository
                        .findBySessionIdOrderByCreatedAtAsc(session.getId());

        List<ConversationInspectorView.TimelineEntry> timeline =
                new ArrayList<>();

        for (ConversationMessageRecord message : messages) {
            timeline.add(
                    new ConversationInspectorView.TimelineEntry(
                            message.getTurnNumber(),
                            "MESSAGE",
                            message.getRole().name(),
                            null,
                            message.getText(),
                            message.getCreatedAt()));
        }

        for (ConversationEventRecord event : events) {
            timeline.add(
                    new ConversationInspectorView.TimelineEntry(
                            event.getTurnNumber(),
                            "EVENT",
                            null,
                            event.getEventType().name(),
                            event.getDetail(),
                            event.getCreatedAt()));
        }

        timeline.sort(
                Comparator.comparing(
                        ConversationInspectorView.TimelineEntry::timestamp));

        return new ConversationInspectorView(
                session.getSessionId(),
                session.getChannel().name(),
                session.getIdentityAssurance().name(),
                session.isEscalated(),
                session.getStartedAt(),
                session.getLastActivityAt(),
                timeline);
    }

    private Map<String, AdminDashboardSummary.ToolMetric> computeToolMetrics() {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, double[]> totalMs = new LinkedHashMap<>();

        for (Meter meter :
                meterRegistry
                        .find("voxticket.tool.call.duration")
                        .meters()) {

            if (meter instanceof Timer timer) {
                String tool =
                        meter.getId().getTag("tool");

                if (tool == null) {
                    continue;
                }

                counts
                        .computeIfAbsent(tool, key -> new long[1])[0]
                        += timer.count();

                totalMs
                        .computeIfAbsent(tool, key -> new double[1])[0]
                        += timer.totalTime(TimeUnit.MILLISECONDS);
            }
        }

        Map<String, AdminDashboardSummary.ToolMetric> result =
                new LinkedHashMap<>();

        for (String tool : counts.keySet()) {
            long count =
                    counts.get(tool)[0];

            double sumMs =
                    totalMs.get(tool)[0];

            result.put(
                    tool,
                    new AdminDashboardSummary.ToolMetric(
                            count,
                            count == 0
                                    ? 0.0
                                    : sumMs / count));
        }

        return result;
    }

    /**
     * Phase 1 provider/model visibility. Aggregates the voxticket.llm.call.duration
     * timers by tier - calls, mean latency, and error outcomes. Pure read of
     * existing metric tags; no routing logic lives here.
     */
    private Map<String, AdminDashboardSummary.LlmCallMetric> computeLlmCallMetrics() {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, double[]> totalMs = new LinkedHashMap<>();
        Map<String, long[]> errors = new LinkedHashMap<>();

        for (Meter meter : meterRegistry.find("voxticket.llm.call.duration").meters()) {
            if (meter instanceof Timer timer) {
                String tier = meter.getId().getTag("tier");
                if (tier == null) {
                    continue;
                }
                long count = timer.count();
                counts.computeIfAbsent(tier, key -> new long[1])[0] += count;
                totalMs.computeIfAbsent(tier, key -> new double[1])[0] += timer.totalTime(TimeUnit.MILLISECONDS);
                if ("error".equals(meter.getId().getTag("outcome"))) {
                    errors.computeIfAbsent(tier, key -> new long[1])[0] += count;
                }
            }
        }

        Map<String, AdminDashboardSummary.LlmCallMetric> result = new LinkedHashMap<>();
        for (String tier : counts.keySet()) {
            long count = counts.get(tier)[0];
            double sumMs = totalMs.get(tier)[0];
            long errorCount = errors.containsKey(tier) ? errors.get(tier)[0] : 0;
            result.put(
                    tier,
                    new AdminDashboardSummary.LlmCallMetric(
                            count,
                            count == 0 ? 0.0 : sumMs / count,
                            errorCount));
        }
        return result;
    }


    private ConversationSummaryView toSummaryView(
            ConversationSessionRecord record) {

        return new ConversationSummaryView(
                record.getSessionId(),
                record.getChannel().name(),
                record.getIdentityAssurance().name(),
                record.isEscalated(),
                record.getStartedAt(),
                record.getLastActivityAt());
    }

    private long sumCountersWhereTag(
            String meterName,
            String tagKey,
            String tagValue) {

        return (long) meterRegistry
                .find(meterName)
                .meters()
                .stream()
                .filter(
                        meter ->
                                tagValue.equals(
                                        meter.getId().getTag(tagKey)))
                .filter(Counter.class::isInstance)
                .mapToDouble(
                        meter ->
                                ((Counter) meter).count())
                .sum();
    }

    private long sumCountersWhereTagPair(
            String meterName,
            String tagKey1,
            String tagValue1,
            String tagKey2,
            String tagValue2) {

        return (long) meterRegistry
                .find(meterName)
                .meters()
                .stream()
                .filter(
                        meter ->
                                tagValue1.equals(
                                        meter.getId()
                                                .getTag(tagKey1))
                                        && tagValue2.equals(
                                        meter.getId()
                                                .getTag(tagKey2)))
                .filter(Counter.class::isInstance)
                .mapToDouble(
                        meter ->
                                ((Counter) meter).count())
                .sum();
    }

    private Map<String, Long> sumCountersByTag(
            String meterName,
            String tagKey) {

        Map<String, Long> result =
                new LinkedHashMap<>();

        for (Meter meter :
                meterRegistry
                        .find(meterName)
                        .meters()) {

            if (meter instanceof Counter counter) {
                String key =
                        meter.getId().getTag(tagKey);

                if (key != null) {
                    result.merge(
                            key,
                            (long) counter.count(),
                            Long::sum);
                }
            }
        }

        return result;
    }

    private Timer findTimer(
            String name,
            Map<String, String> tags) {

        Search search =
                meterRegistry.find(name);

        for (Map.Entry<String, String> tag :
                tags.entrySet()) {

            search =
                    search.tag(
                            tag.getKey(),
                            tag.getValue());
        }

        return search.timer();
    }

    /**
     * Never report a misleading zero percentile.
     *
     * If no timer exists, no turns were recorded, or Micrometer's rolling
     * percentile estimate is unavailable/expired, return null so the
     * dashboard can display N/A.
     */
    private Double extractReliablePercentile(
            Timer timer,
            double percentile) {

        if (timer == null || timer.count() == 0) {
            return null;
        }

        double value =
                timer.percentile(
                        percentile,
                        TimeUnit.MILLISECONDS);

        return value <= 0.0
                ? null
                : value;
    }
}