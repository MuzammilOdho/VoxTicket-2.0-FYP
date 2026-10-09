package com.voxticket.procedure;

import com.voxticket.conversation.ConversationSession;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Pass 2B: every procedure tool returns {@link ProcedureToolResult} - the
 * model-facing representation - instead of the raw coordinator
 * {@link ProcedureOutcome}. The deterministic {@code ProcedureOutcome.message()}
 * stays available to {@code ConversationRuntime} for direct customer responses;
 * the model only ever sees stable codes, the next action, and whitelisted
 * structured facts.
 */
public class ProcedureRequestTools {

    private final ProcedureCoordinator procedureCoordinator;
    private final ConversationSession session;

    public ProcedureRequestTools(ProcedureCoordinator procedureCoordinator, ConversationSession session) {
        this.procedureCoordinator = procedureCoordinator;
        this.session = session;
    }

    @Tool(description = "Start the deterministic cancellation procedure for an owned order. Use only when the customer "
            + "wants to cancel now, not for hypothetical or policy questions. The returned procedure state determines "
            + "what happens next; this call by itself does not prove cancellation succeeded.")
    public ProcedureToolResult requestCancellation(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureType.CANCELLATION.name(),
                procedureCoordinator.startCancellation(session, orderReference));
    }

    @Tool(description = "Start or continue the deterministic return procedure for an item in an owned order. Use when "
            + "the customer wants to return something now. Call even when the item or reason is not yet known; the "
            + "procedure result identifies missing information. This call by itself does not prove the return succeeded.")
    public ProcedureToolResult requestReturn(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(required = false, description = "Item name in the customer's own words; blank if unknown or the order has one item") String itemReference,
            @ToolParam(required = false, description = "Why the customer is returning it, in their own words; blank if not said yet") String reason,
            @ToolParam(required = false, description = "How many units to return, e.g. \"2\"; blank or \"1\" for a single unit") String quantity) {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureType.RETURN.name(),
                procedureCoordinator.startReturn(session, orderReference, itemReference, reason, quantity));
    }
    
    @Tool(description = "Start or continue the deterministic claim procedure for an item problem such as damaged, "
            + "defective, incorrect, or missing goods. Call even when the item or problem description is incomplete; "
            + "the procedure result identifies missing information. The claim is not filed until server-controlled "
            + "confirmation succeeds.")
    public ProcedureToolResult requestClaim(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(required = false, description = "Item name in the customer's own words; blank if unknown or the order has one item") String itemReference,
            @ToolParam(required = false, description = "What happened to the item, in the customer's own words; blank if not said yet") String problem) {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureType.CLAIM.name(),
                procedureCoordinator.startClaim(session, orderReference, itemReference, problem));
    }

    @Tool(description = "Escalate the conversation to human support when the customer explicitly requests a "
            + "person/supervisor or says automated support cannot help.")
    public ProcedureToolResult requestHumanSupport(@ToolParam(description = "Brief reason for the escalation") String reason) {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureToolResultMapper.HUMAN_SUPPORT,
                procedureCoordinator.requestHumanSupport(session, reason));
    }
}
