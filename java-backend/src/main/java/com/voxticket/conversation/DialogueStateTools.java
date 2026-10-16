package com.voxticket.conversation;

import com.voxticket.agent.ToolCallTimeout;
import com.voxticket.agent.ToolSideEffect;
import com.voxticket.config.TimeoutProperties;
import com.voxticket.observability.TurnTrace;
import com.voxticket.procedure.ProcedureCoordinator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * P2.1: the constrained dialogue-state proposal tool.
 *
 * <p>The LLM may propose dialogue-state updates <em>only</em> through this
 * tool. {@link DialogueStateUpdater} validates every proposal (slot
 * vocabulary, value formats, confidence clamping, provenance rules,
 * classification validation) before anything lands in the
 * {@link DialogueState}. This tool never executes a commerce mutation,
 * issues OTP, or touches business state - deterministic code owns all
 * transitions. The safety boundary does not move.
 */
public class DialogueStateTools {

    private final ProcedureCoordinator procedureCoordinator;
    private final ConversationSession session;
    private final TimeoutProperties timeouts;

    public DialogueStateTools(ProcedureCoordinator procedureCoordinator, ConversationSession session,
                              TimeoutProperties timeouts) {
        this.procedureCoordinator = procedureCoordinator;
        this.session = session;
        this.timeouts = timeouts;
    }

    @Tool(description = "Propose a dialogue-state update: what the caller wants and how sure you are. "
            + "Actions: SET_SLOT (record a fact: slotName, slotValue, confidence 0-1, provenance USER_SAID/INFERRED), "
            + "CONFIRM_SLOT (the caller confirmed a recorded slotName - makes it certain), "
            + "MARK_CORRECTION (the caller corrected a slot: slotName, corrected slotValue), "
            + "PUSH_REQUEST (queue a procedure request: procedureType CANCELLATION/RETURN/CLAIM, orderNumber), "
            + "POP_REQUEST (withdraw the top queued request), "
            + "CLASSIFY_INTERRUPTION (how this utterance relates to the interrupted turn: CORRECTION/CONTINUATION/NEW_INTENT/WITHDRAWAL, "
            + "with slotName and the corrected slotValue for CORRECTION). "
            + "Known slots: order_reference, ticket_reference, item_reference, problem_description, return_reason, return_quantity. "
            + "Every proposal is validated by deterministic code - unknown slots are rejected, confidence is clamped, "
            + "and USER_SAID needs a matching span in what was actually heard. "
            + "Use this whenever the caller states, confirms, or corrects a reference, queues another request, "
            + "or interrupts the previous turn. The result tells you what was recorded and what to ask next.")
    public String updateDialogueState(
            @ToolParam(description = "One of: SET_SLOT, CONFIRM_SLOT, MARK_CORRECTION, PUSH_REQUEST, POP_REQUEST, CLASSIFY_INTERRUPTION") String action,
            @ToolParam(required = false, description = "Slot name, e.g. order_reference") String slotName,
            @ToolParam(required = false, description = "Slot value, e.g. ORD-10001") String slotValue,
            @ToolParam(required = false, description = "How confident you are, 0-1") Double confidence,
            @ToolParam(required = false, description = "USER_SAID or INFERRED (TOOL_READ is reserved for system lookups)") String provenance,
            @ToolParam(required = false, description = "For PUSH_REQUEST: CANCELLATION, RETURN, or CLAIM") String procedureType,
            @ToolParam(required = false, description = "For PUSH_REQUEST: the order number, e.g. ORD-10001") String orderNumber,
            @ToolParam(required = false, description = "For CLASSIFY_INTERRUPTION: CORRECTION, CONTINUATION, NEW_INTENT, or WITHDRAWAL") String interruptionClass) {
        session.markToolInvoked();
        long start = System.nanoTime();
        try {
            // P0.1: bounded like every tool. P1.2: ownership-checked via
            // TurnContext - a preempted turn's proposal is deferred, never
            // applied. Side-effect READ_ONLY: proposals never execute a
            // commerce mutation; deterministic code owns all transitions.
            DialogueStateUpdateResult result = ToolCallTimeout.call("updateDialogueState",
                    timeouts.toolTimeout(), () -> applyProposal(action, slotName, slotValue, confidence,
                            provenance, procedureType, orderNumber, interruptionClass));
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            traceToolCall("updateDialogueState", result.ok() ? "OK" : "REJECTED", durationMs);
            return result.message();
        } catch (TurnAbortedException aborted) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            traceToolCall("updateDialogueState", "CANCELLED", durationMs);
            throw aborted;
        }
    }

    private DialogueStateUpdateResult applyProposal(String action, String slotName, String slotValue,
                                                   Double confidence, String provenance, String procedureType,
                                                   String orderNumber, String interruptionClass) {
        if (action == null || action.isBlank()) {
            return DialogueStateUpdateResult.rejected(
                    "An action is required. Use SET_SLOT, CONFIRM_SLOT, MARK_CORRECTION, "
                            + "PUSH_REQUEST, POP_REQUEST, or CLASSIFY_INTERRUPTION.");
        }
        DialogueStateAction parsed;
        try {
            parsed = DialogueStateAction.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return DialogueStateUpdateResult.rejected(
                    "Unknown action '" + action + "'. Use SET_SLOT, CONFIRM_SLOT, MARK_CORRECTION, "
                            + "PUSH_REQUEST, POP_REQUEST, or CLASSIFY_INTERRUPTION.");
        }
        DialogueStateProposal proposal = new DialogueStateProposal(parsed, blankToNull(slotName),
                blankToNull(slotValue), confidence, blankToNull(provenance), blankToNull(procedureType),
                blankToNull(orderNumber), blankToNull(interruptionClass));
        return DialogueStateUpdater.apply(session, proposal, procedureCoordinator::queuePendingRequest);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void traceToolCall(String toolName, String resultCode, long durationMs) {
        TurnTrace.Builder builder = session.getActiveTraceBuilder();
        if (builder != null) {
            builder.addToolCall(toolName, resultCode, (double) durationMs, ToolSideEffect.READ_ONLY);
        }
    }
}
