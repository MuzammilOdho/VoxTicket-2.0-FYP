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
    void recordModelSelectionRegistersACounterWithReason() {
        metrics.recordModelSelection("TIER_1", "openai/gpt-oss-20b", "default");

        var counter = registry.find("voxticket.model.selection").tag("tier", "TIER_1").tag("reason", "default").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
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
}