package com.voxticket.procedure;

import com.voxticket.agent.ToolCallTimeout;
import com.voxticket.agent.ToolSideEffect;
import com.voxticket.config.TimeoutProperties;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.observability.TurnTrace;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Pass 2D-B: safe control tools for the single-active-procedure model.
 *
 * <p>Neither tool executes a commerce mutation, issues OTP, or touches order
 * / return / claim business state. They only manage the session's procedure
 * slots, and every state transition is owned by deterministic Java in
 * {@link ProcedureCoordinator} - the model only decides <em>when</em> the
 * customer asked for them.
 */
public class ProcedureControlTools {

    private final ProcedureCoordinator procedureCoordinator;
    private final ConversationSession session;
    private final TimeoutProperties timeouts;

    public ProcedureControlTools(ProcedureCoordinator procedureCoordinator, ConversationSession session, TimeoutProperties timeouts) {
        this.procedureCoordinator = procedureCoordinator;
        this.session = session;
        this.timeouts = timeouts;
    }

    @Tool(description = "Abandon the currently pending procedure WITHOUT executing it. Use only when the customer "
            + "explicitly asks to abandon, replace, correct, or stop the currently pending action - for example "
            + "'forget this return', 'never mind, forget this', 'not this item, the other one', or 'don't do this, do "
            + "the claim instead'. This drops the pending request and invalidates any verification code tied to it; "
            + "it never executes a commerce mutation and is NOT the same as cancelling an order's business state. "
            + "If the customer says 'cancel my order', that is requestCancellation, not this tool.")
    public ProcedureToolResult abandonActiveProcedure() {
        session.markToolInvoked();
        // P0.1: bounded like every tool. P1.2: mutating (drops the pending
        // request) - ownership-checked and trace-marked on cancellation via
        // invokeMutating.
        return invokeMutating("abandonActiveProcedure", () ->
                ProcedureToolResultMapper.toToolResult(ProcedureToolResultMapper.PROCEDURE_CONTROL,
                        procedureCoordinator.abandonActiveProcedure(session)));
    }

    @Tool(description = "Discard the queued (deferred) procedure request WITHOUT executing it. Use only when the "
            + "customer explicitly asks to drop the queued request - for example 'forget the claim' while another "
            + "action is still in progress. This never affects the currently active procedure and never executes anything.")
    public ProcedureToolResult discardDeferredIntent() {
        session.markToolInvoked();
        // P0.1: bounded like every tool. P1.2: mutating (discards queued
        // state) - ownership-checked and trace-marked on cancellation via
        // invokeMutating.
        return invokeMutating("discardDeferredIntent", () ->
                ProcedureToolResultMapper.toToolResult(ProcedureToolResultMapper.PROCEDURE_CONTROL,
                        procedureCoordinator.discardDeferredIntent(session)));
    }

    /**
     * P1.2: runs a mutating tool under the turn's ownership. The timeout
     * wrapper defers a preempted turn's tool (never starts it) and cancels
     * in-flight work; the resulting {@link TurnAbortedException} is marked
     * {@code CANCELLED} on the trace and rethrown - never the sanitized
     * {@code TOOL_EXECUTION_FAILED} path, so a dead turn cannot keep talking
     * to the model.
     */
    private ProcedureToolResult invokeMutating(String toolName,
            java.util.concurrent.Callable<ProcedureToolResult> call) {
        long start = System.nanoTime();
        try {
            return ToolCallTimeout.call(toolName, timeouts.toolTimeout(), call);
        } catch (TurnAbortedException aborted) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            TurnTrace.Builder builder = session.getActiveTraceBuilder();
            if (builder != null) {
                builder.addToolCall(toolName, "CANCELLED", (double) durationMs, ToolSideEffect.MUTATING);
            }
            throw aborted;
        }
    }
}
