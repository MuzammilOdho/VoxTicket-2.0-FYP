package com.voxticket.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TurnMetrics {

    private final MeterRegistry registry;
    // Latency optimisation: previously every record* call built a new
    // builder and resolved the meter under a registry lock, on the turn
    // path, for every turn / model selection / tool call / RAG search.
    // Tag combinations are bounded (tier / provider / model / reason /
    // tool / result codes), so meters are cached by name + tags here.
    // Behaviour is unchanged: the same meter name, tags, percentiles and
    // expiry as before, just resolved once per tag combination. Under a
    // race two threads may register the same meter twice; Micrometer
    // returns the same meter for the same name + tags, and putIfAbsent
    // keeps exactly one cached instance.
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

    public TurnMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private Timer cachedTimer(String key, Timer.Builder builder) {
        Timer existing = timers.get(key);
        if (existing != null) {
            return existing;
        }
        Timer created = builder.register(registry);
        Timer previous = timers.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    private Counter cachedCounter(String key, Counter.Builder builder) {
        Counter existing = counters.get(key);
        if (existing != null) {
            return existing;
        }
        Counter created = builder.register(registry);
        Counter previous = counters.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    private DistributionSummary cachedSummary(String key, DistributionSummary.Builder builder) {
        DistributionSummary existing = summaries.get(key);
        if (existing != null) {
            return existing;
        }
        DistributionSummary created = builder.register(registry);
        DistributionSummary previous = summaries.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    public void recordTurn(Duration duration, String channel, String outcome) {
        cachedTimer("voxticket.turn.duration|" + channel + "|" + outcome,
                Timer.builder("voxticket.turn.duration")
                        .tag("channel", channel)
                        .tag("outcome", outcome)
                        .publishPercentiles(0.5, 0.95)
                        .distributionStatisticExpiry(Duration.ofMinutes(30)))
                .record(duration);
    }

    /**
     * P0: an explicitly aborted turn (voice client disconnected mid-turn).
     * Recorded separately from {@code voxticket.turn.duration} because an
     * aborted turn never completes - it has no meaningful duration sample.
     */
    public void recordTurnAborted(String channel) {
        cachedCounter("voxticket.turn.aborted|" + channel,
                Counter.builder("voxticket.turn.aborted")
                        .tag("channel", channel)
                        .description("Turns aborted by client disconnect (barge-in / hang-up)"))
                .increment();
    }

    public void recordModelSelection(String tier, String provider, String model, String reason) {
        cachedCounter("voxticket.model.selection|" + tier + "|" + provider + "|" + model + "|" + reason,
                Counter.builder("voxticket.model.selection")
                        .tag("tier", tier).tag("provider", provider).tag("model", model).tag("reason", reason))
                .increment();
    }

    /**
     * Phase 2: the router's own decision, recorded with bounded-cardinality tags only.
     * The margin distribution skips non-semantic decisions (NaN) - recording zeros would
     * corrupt the distribution.
     */
    public void recordRoutingDecision(String strategy, String tier, String reason, double margin) {
        cachedCounter("voxticket.routing.decision|" + strategy + "|" + tier + "|" + reason,
                Counter.builder("voxticket.routing.decision")
                        .tag("strategy", strategy).tag("tier", tier).tag("reason", reason))
                .increment();
        if (!Double.isNaN(margin)) {
            // Margin is signed (complex - simple); the direction is already encoded in the
            // reason tag (SEMANTIC_SIMPLE/COMPLEX/AMBIGUOUS), so the distribution records the
            // non-negative confidence magnitude. Raw scores/margins never become tags.
            cachedSummary("voxticket.routing.margin|" + strategy,
                    DistributionSummary.builder("voxticket.routing.margin")
                            .tag("strategy", strategy)
                            .publishPercentiles(0.5, 0.95)
                            .distributionStatisticExpiry(Duration.ofMinutes(30)))
                    .record(Math.abs(margin));
        }
    }

    public void recordLlmCall(Duration duration, String tier, String provider, String model, String outcome) {
        cachedTimer("voxticket.llm.call.duration|" + tier + "|" + provider + "|" + model + "|" + outcome,
                Timer.builder("voxticket.llm.call.duration")
                        .tag("tier", tier).tag("provider", provider).tag("model", model).tag("outcome", outcome))
                .record(duration);
    }

    public void recordTokenUsage(String provider, String model, String tokenType, long count) {
        cachedCounter("voxticket.llm.tokens|" + provider + "|" + model + "|" + tokenType,
                Counter.builder("voxticket.llm.tokens")
                        .tag("provider", provider).tag("model", model).tag("type", tokenType))
                .increment(count);
    }

    public void recordToolCall(Duration duration, String tool, String result) {
        cachedTimer("voxticket.tool.call.duration|" + tool + "|" + result,
                Timer.builder("voxticket.tool.call.duration").tag("tool", tool).tag("result", result))
                .record(duration);
    }

    /** An unexpected tool exception, sanitized before reaching the model. Counted separately from normal tool results. */
    public void recordToolError(String tool, String code) {
        cachedCounter("voxticket.tool.error|" + tool + "|" + code,
                Counter.builder("voxticket.tool.error").tag("tool", tool).tag("code", code))
                .increment();
    }

    public void recordRagSearch(Duration duration, int retrievedCount) {
        cachedTimer("voxticket.rag.search.duration",
                Timer.builder("voxticket.rag.search.duration"))
                .record(duration);
        registry.summary("voxticket.rag.retrieved.count").record(retrievedCount);
    }

    public void recordProcedureOutcome(String type, String code, boolean success) {
        cachedCounter("voxticket.procedure.outcome|" + type + "|" + code + "|" + success,
                Counter.builder("voxticket.procedure.outcome").tag("type", type).tag("code", code).tag("success", String.valueOf(success)))
                .increment();
    }

    public void recordPromptGuardOutcome(Duration duration, String implementation, boolean suspicious, boolean fallback) {
        cachedTimer("voxticket.prompt_guard.duration|" + implementation + "|" + suspicious + "|" + fallback,
                Timer.builder("voxticket.prompt_guard.duration")
                        .tag("implementation", implementation).tag("suspicious", String.valueOf(suspicious)).tag("fallback", String.valueOf(fallback)))
                .record(duration);
        cachedCounter("voxticket.prompt_guard.outcome|" + implementation + "|" + suspicious,
                Counter.builder("voxticket.prompt_guard.outcome").tag("implementation", implementation).tag("suspicious", String.valueOf(suspicious)))
                .increment();
    }
}
