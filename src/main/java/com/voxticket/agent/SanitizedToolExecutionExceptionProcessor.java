package com.voxticket.agent;

import com.voxticket.observability.TurnMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.stereotype.Component;

/**
 * Converts an unexpected tool failure into a fixed, provider-safe JSON
 * payload for the model.
 *
 * <p>Expected domain outcomes (NOT_ELIGIBLE, ITEM_REQUIRED,
 * IDENTITY_NOT_VERIFIED, VERIFICATION_REQUIRED, ...) are normal tool return
 * values and never reach this processor - only genuine exceptions do. When
 * one escapes a tool, the model must receive valid JSON it can parse (the
 * native Google provider JSON-parses tool responses and fails the whole
 * turn on raw text), but it must never see internals.
 *
 * <p>The payload is therefore a constant: no exception message, no
 * Hibernate/SQL detail, no stack trace, no IDs, no secrets, no internal
 * class names. The exception type is logged server-side for operators only.
 */
@Component
public class SanitizedToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    static final String ERROR_CODE = "TOOL_EXECUTION_FAILED";
    static final String ERROR_MESSAGE = "The operation could not be completed. Do not invent a result.";

    /** Fixed payload - deliberately contains nothing derived from the exception. */
    static final String SAFE_ERROR_JSON =
            "{\"error\":{\"code\":\"" + ERROR_CODE + "\",\"message\":\"" + ERROR_MESSAGE + "\"}}";

    private static final Logger log = LoggerFactory.getLogger(SanitizedToolExecutionExceptionProcessor.class);

    private final TurnMetrics turnMetrics;

    public SanitizedToolExecutionExceptionProcessor(TurnMetrics turnMetrics) {
        this.turnMetrics = turnMetrics;
    }

    @Override
    public String process(ToolExecutionException exception) {
        String toolName = exception.getToolDefinition() != null ? exception.getToolDefinition().name() : "unknown";
        Throwable cause = exception.getCause();
        // Server-side only: the model never sees this.
        log.warn("event=tool_execution_failed tool={} errorType={}", toolName,
                cause != null ? cause.getClass().getSimpleName() : exception.getClass().getSimpleName());
        turnMetrics.recordToolError(toolName, ERROR_CODE);
        return SAFE_ERROR_JSON;
    }
}
