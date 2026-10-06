package com.voxticket.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TurnMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TurnMetrics metrics = new TurnMetrics(registry);

    @Test
    void recordTurnRegistersATimerWithTheExpectedTags() {
        metrics.recordTurn(Duration.ofMillis(150), "CHAT", "normal");

        var timer = registry.find("voxticket.turn.duration").tag("channel", "CHAT").tag("outcome", "normal").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
    
    @Test
    void recordProcedureOutcomeRegistersACounterTaggedBySuccess() {
        metrics.recordProcedureOutcome("CANCELLATION", "CANCELLED", true);

        var counter = registry.find("voxticket.procedure.outcome").tag("type", "CANCELLATION").tag("success", "true").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordPromptGuardOutcomeRegistersBothTimerAndCounter() {
        metrics.recordPromptGuardOutcome(Duration.ofMillis(20), "heuristic", true, false);

        assertThat(registry.find("voxticket.prompt_guard.duration").timer()).isNotNull();
        assertThat(registry.find("voxticket.prompt_guard.outcome").counter()).isNotNull();
    }

    @Test
    void recordTurnEnablesPercentileQueriesAfterEnoughSamples() {
        for (int i = 1; i <= 20; i++) {
            metrics.recordTurn(Duration.ofMillis(i * 5L), "CHAT", "normal");
        }

        var timer = registry.find("voxticket.turn.duration").tag("channel", "CHAT").tag("outcome", "normal").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.percentile(0.5, java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
    }

    @Test
    void recordModelSelectionRegistersACounterWithTierProviderModelAndReason() {
        metrics.recordModelSelection("TIER_1", "GROQ", "openai/gpt-oss-20b", "default");

        var counter = registry.find("voxticket.model.selection")
                .tag("tier", "TIER_1").tag("provider", "GROQ").tag("model", "openai/gpt-oss-20b").tag("reason", "default")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmCallRegistersATimerTaggedWithTierProviderModelAndOutcome() {
        metrics.recordLlmCall(Duration.ofMillis(120), "TIER_2", "CEREBRAS", "gpt-oss-120b", "success");

        var timer = registry.find("voxticket.llm.call.duration")
                .tag("tier", "TIER_2").tag("provider", "CEREBRAS").tag("model", "gpt-oss-120b").tag("outcome", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void recordTokenUsageRegistersACounterTaggedWithProviderModelAndType() {
        metrics.recordTokenUsage("GROQ", "openai/gpt-oss-20b", "prompt", 42L);

        var counter = registry.find("voxticket.llm.tokens")
                .tag("provider", "GROQ").tag("model", "openai/gpt-oss-20b").tag("type", "prompt")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(42.0);
    }

}