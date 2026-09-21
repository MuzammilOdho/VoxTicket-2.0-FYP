package com.voxticket.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class TurnMetrics {

    private final MeterRegistry registry;

    public TurnMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * CORRECTION from last round: minimumExpectedValue/maximumExpectedValue only affect
     * publishPercentileHistogram() bucket generation for external aggregation systems - they do
     * NOT affect the client-side .percentile() value this app reads directly. Verified against
     * Micrometer issue #3298: a Timer's client-side percentile estimate decays to 0.0 after its
     * distribution-statistic window expires, while count()/max() persist far longer - exactly the
     * observed symptom (0 despite non-zero turns). distributionStatisticExpiry is the real,
     * documented knob for that window.
     */
    public void recordTurn(Duration duration, String channel, String outcome) {
        Timer.builder("voxticket.turn.duration")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95)
                .distributionStatisticExpiry(Duration.ofMinutes(30))
                .register(registry)
                .record(duration);
    }

    public void recordModelSelection(String tier, String model, String reason) {
        Counter.builder("voxticket.model.selection").tag("tier", tier).tag("model", model).tag("reason", reason).register(registry).increment();
    }

    public void recordLlmCall(Duration duration, String tier, String model, String outcome) {
        Timer.builder("voxticket.llm.call.duration").tag("tier", tier).tag("model", model).tag("outcome", outcome).register(registry).record(duration);
    }

    public void recordTokenUsage(String model, String tokenType, long count) {
        Counter.builder("voxticket.llm.tokens").tag("model", model).tag("type", tokenType).register(registry).increment(count);
    }

    public void recordToolCall(Duration duration, String tool, String result) {
        Timer.builder("voxticket.tool.call.duration").tag("tool", tool).tag("result", result).register(registry).record(duration);
    }

    public void recordRagSearch(Duration duration, int retrievedCount) {
        Timer.builder("voxticket.rag.search.duration").register(registry).record(duration);
        registry.summary("voxticket.rag.retrieved.count").record(retrievedCount);
    }

    public void recordProcedureOutcome(String type, String code, boolean success) {
        Counter.builder("voxticket.procedure.outcome").tag("type", type).tag("code", code).tag("success", String.valueOf(success)).register(registry).increment();
    }

    public void recordPromptGuardOutcome(Duration duration, String implementation, boolean suspicious, boolean fallback) {
        Timer.builder("voxticket.prompt_guard.duration")
                .tag("implementation", implementation).tag("suspicious", String.valueOf(suspicious)).tag("fallback", String.valueOf(fallback))
                .register(registry).record(duration);
        Counter.builder("voxticket.prompt_guard.outcome").tag("implementation", implementation).tag("suspicious", String.valueOf(suspicious))
                .register(registry).increment();
    }
}