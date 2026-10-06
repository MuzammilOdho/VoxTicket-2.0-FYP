package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.observability.TurnMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * An unexpected tool exception must reach the model as fixed, provider-safe
 * JSON - never as raw exception text. Regression test for the native Google
 * provider failing the whole turn with "Failed to parse JSON" when a
 * Hibernate lazy-proxy message leaked through as a tool response.
 */
class SanitizedToolExecutionExceptionProcessorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SanitizedToolExecutionExceptionProcessor processor =
            new SanitizedToolExecutionExceptionProcessor(new TurnMetrics(meterRegistry));

    private static ToolExecutionException toolFailure(String toolName, Throwable cause) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(toolName);
        return new ToolExecutionException(definition, cause);
    }

    @Test
    void unexpectedToolFailureReturnsFixedProviderSafeJson() {
        String result = processor.process(toolFailure("requestReturn", new RuntimeException("boom")));

        assertThat(result).isEqualTo(
                "{\"error\":{\"code\":\"TOOL_EXECUTION_FAILED\",\"message\":\"The operation could not be completed. Do not invent a result.\"}}");
    }

    @Test
    void noRawExceptionDetailLeaksToTheModel() {
        // The exact shape of the production failure: a lazy-proxy message
        // carrying an entity class, a UUID, and SQL-adjacent text.
        String nastyMessage = "Could not initialize proxy "
                + "[com.voxticket.persistence.entity.ReturnRequest#b2afa4fe-8226-413d-97f5-0e27f19f5902] - no session; "
                + "SQL [select * from return_request where id='b2afa4fe']; "
                + "at com.voxticket.policy.ReturnPolicyService.evaluate(ReturnPolicyService.java:91)";

        String result = processor.process(
                toolFailure("getMyOrderContext", new LazyInitializationException(nastyMessage)));

        assertThat(result)
                .doesNotContain("proxy", "ReturnRequest", "b2afa4fe", "no session", "select",
                        "ReturnPolicyService", "LazyInitialization", "Hibernate", "SQL");
        assertThat(result).isEqualTo(SanitizedToolExecutionExceptionProcessor.SAFE_ERROR_JSON);
    }

    @Test
    void sanitizedFailureIsCountedPerTool() {
        processor.process(toolFailure("requestReturn", new RuntimeException("boom")));
        processor.process(toolFailure("requestReturn", new RuntimeException("boom")));
        processor.process(toolFailure("getMyOrderContext", new RuntimeException("boom")));

        var returnCounter = meterRegistry.find("voxticket.tool.error")
                .tags("tool", "requestReturn", "code", "TOOL_EXECUTION_FAILED").counter();
        var contextCounter = meterRegistry.find("voxticket.tool.error")
                .tags("tool", "getMyOrderContext", "code", "TOOL_EXECUTION_FAILED").counter();

        assertThat(returnCounter).isNotNull();
        assertThat(returnCounter.count()).isEqualTo(2.0);
        assertThat(contextCounter).isNotNull();
        assertThat(contextCounter.count()).isEqualTo(1.0);
    }
}
