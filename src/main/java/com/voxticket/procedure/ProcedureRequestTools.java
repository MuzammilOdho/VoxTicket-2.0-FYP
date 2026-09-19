package com.voxticket.procedure;

import com.voxticket.conversation.ConversationSession;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ProcedureRequestTools {

    private final ProcedureCoordinator procedureCoordinator;
    private final ConversationSession session;

    public ProcedureRequestTools(ProcedureCoordinator procedureCoordinator, ConversationSession session) {
        this.procedureCoordinator = procedureCoordinator;
        this.session = session;
    }

    @Tool(description = "Begin a cancellation request for one of the customer's own orders. This starts a guarded process - "
            + "it does NOT cancel the order by itself. The customer must still explicitly confirm afterward.")
    public Object requestCancellation(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return procedureCoordinator.startCancellation(session, orderReference);
    }

    @Tool(description = "Begin a return request for a specific item on one of the customer's own orders. This starts a guarded process - "
            + "it does NOT create the return by itself. The customer must still explicitly confirm afterward.")
    public Object requestReturn(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(description = "Which item, described naturally in the customer's own words - product name, color, or any other "
                    + "distinguishing detail. NEVER a SKU or internal ID. Leave blank if the order only has one item or the customer hasn't said yet.")
            String itemReference,
            @ToolParam(description = "Why the customer is returning it, in their own words. Leave blank if they haven't said yet.") String reason) {
        return procedureCoordinator.startReturn(session, orderReference, itemReference, reason);
    }

    @Tool(description = "Report a problem with a specific item on one of the customer's own orders - damaged, defective, wrong item, or "
            + "missing item. This starts a guarded process - it does NOT file the claim by itself. The customer must still explicitly confirm afterward.")
    public Object reportOrderProblem(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(description = "Which item, described naturally in the customer's own words - product name, color, or any other "
                    + "distinguishing detail. NEVER a SKU or internal ID. Leave blank if the order only has one item or the customer hasn't said yet.")
            String itemReference,
            @ToolParam(description = "Description of the problem, in the customer's own words. Leave blank if they haven't said yet.") String problem) {
        return procedureCoordinator.startClaim(session, orderReference, itemReference, problem);
    }

    @Tool(description = "Escalate to a human support agent. Use this when the customer explicitly asks for a person, a supervisor, "
            + "or says this system can't help them.")
    public Object requestHumanSupport(@ToolParam(description = "Brief reason for the escalation") String reason) {
        return procedureCoordinator.requestHumanSupport(session, reason);
    }
}