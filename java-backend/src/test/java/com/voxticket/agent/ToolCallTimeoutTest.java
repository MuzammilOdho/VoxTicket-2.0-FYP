package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.SessionOwnership;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.TurnHandle;
import com.voxticket.observability.TurnMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * P0.1: tool calls are bounded by {@link ToolCallTimeout}. A hanging tool must surface as a
 * {@link ToolTimeoutException} so the existing {@link SanitizedToolExecutionExceptionProcessor}
 * converts it into the fixed safe payload and the turn continues.
 */
class ToolCallTimeoutTest {

    @Test
    void fastWorkReturnsItsResult() throws Exception {
        String result = ToolCallTimeout.call("fast-tool", Duration.ofSeconds(5), () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void hangingWorkThrowsToolTimeoutExceptionNamingTheTool() {
        assertThatThrownBy(() -> ToolCallTimeout.call("slow-tool", Duration.ofMillis(100), () -> {
            Thread.sleep(30_000);
            return "never";
        }))
                .isInstanceOf(ToolTimeoutException.class)
                .hasMessageContaining("slow-tool");
    }

    @Test
    void toolErrorsPropagateUnchanged() {
        RuntimeException boom = new RuntimeException("boom");

        assertThatThrownBy(() -> ToolCallTimeout.call("t", Duration.ofSeconds(5), () -> {
            throw boom;
        })).isSameAs(boom);
    }

    @Test
    void checkedToolErrorsAreWrappedPreservingCause() {
        Exception checked = new Exception("checked");

        assertThatThrownBy(() -> ToolCallTimeout.call("t", Duration.ofSeconds(5), () -> {
            throw checked;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tool call failed: t")
                .hasCause(checked);
    }

    @Test
    void timeoutExceptionCarriesToolNameAndBudget() {
        ToolTimeoutException timeout = new ToolTimeoutException("slow-tool", Duration.ofSeconds(10));

        assertThat(timeout.getToolName()).isEqualTo("slow-tool");
        assertThat(timeout.getMessage()).contains("slow-tool").contains("10");
    }

    @Test
    void timeoutFlowsIntoTheSameSanitizedEnvelopeAsOtherToolFailures() {
        SanitizedToolExecutionExceptionProcessor processor =
                new SanitizedToolExecutionExceptionProcessor(mock(TurnMetrics.class));
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("slow-tool");
        ToolExecutionException wrapped =
                new ToolExecutionException(definition, new ToolTimeoutException("slow-tool", Duration.ofSeconds(10)));

        assertThat(processor.process(wrapped)).isEqualTo(SanitizedToolExecutionExceptionProcessor.SAFE_ERROR_JSON);
    }

    @Test
    void nullHandleKeepsThePreP1Behavior() throws Exception {
        String result = ToolCallTimeout.call("tool", Duration.ofSeconds(5), () -> "ok", null);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void toolStartedAfterOwnershipLossThrowsTurnAbortedException() {
        SessionOwnership ownership = new SessionOwnership();
        SessionOwnership.Lease older = ownership.acquire("defer-s", 1, 2000);
        TurnHandle staleHandle = older.handle();
        ownership.acquire("defer-s", 2, 2000);

        AtomicBoolean workRan = new AtomicBoolean(false);
        assertThatThrownBy(() -> ToolCallTimeout.call("deferred-tool", Duration.ofSeconds(5), () -> {
                    workRan.set(true);
                    return "must not run";
                }, staleHandle))
                .isInstanceOf(TurnAbortedException.class);

        // Deferred means never started: the work is not even submitted.
        assertThat(workRan.get()).isFalse();
    }

    @Test
    void turnAbortedExceptionIsNeverSanitized() {
        SanitizedToolExecutionExceptionProcessor processor =
                new SanitizedToolExecutionExceptionProcessor(mock(TurnMetrics.class));
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("deferred-tool");
        TurnAbortedException aborted = new TurnAbortedException(new IllegalStateException("superseded"));
        ToolExecutionException wrapped = new ToolExecutionException(definition, aborted);

        // The runtime must see the abort as an abort - never a
        // TOOL_EXECUTION_FAILED payload that would let a dead turn keep
        // talking to the model.
        assertThatThrownBy(() -> processor.process(wrapped)).isSameAs(aborted);
    }
}
