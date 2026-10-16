package com.voxticket.agent;

/**
 * P0.1: marker for a tool invocation that exceeded its time budget.
 *
 * <p>Unchecked so it propagates out of {@code @Tool} methods exactly like
 * any other unexpected tool failure: the framework's
 * {@link SanitizedToolExecutionExceptionProcessor} converts it into the fixed
 * {@code TOOL_EXECUTION_FAILED} payload for the model. The exception type is
 * logged server-side for operators only.
 */
public class ToolTimeoutException extends RuntimeException {

    private final String toolName;

    public ToolTimeoutException(String toolName, java.time.Duration timeout) {
        super("tool call timed out: " + toolName + " (budget " + timeout.toMillis() + "ms)");
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
