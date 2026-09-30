package com.voxticket.procedure;

import com.voxticket.conversation.ConversationSession;
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

    public ProcedureControlTools(ProcedureCoordinator procedureCoordinator, ConversationSession session) {
        this.procedureCoordinator = procedureCoordinator;
        this.session = session;
    }

    @Tool(description = "Abandon the currently pending procedure WITHOUT executing it. Use only when the customer "
            + "explicitly asks to abandon, replace, correct, or stop the currently pending action - for example "
            + "'forget this return', 'never mind, forget this', 'not this item, the other one', or 'don't do this, do "
            + "the claim instead'. This drops the pending request and invalidates any verification code tied to it; "
            + "it never executes a commerce mutation and is NOT the same as cancelling an order's business state. "
            + "If the customer says 'cancel my order', that is requestCancellation, not this tool.")
    public ProcedureToolResult abandonActiveProcedure() {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureToolResultMapper.PROCEDURE_CONTROL,
                procedureCoordinator.abandonActiveProcedure(session));
    }

    @Tool(description = "Discard the queued (deferred) procedure request WITHOUT executing it. Use only when the "
            + "customer explicitly asks to drop the queued request - for example 'forget the claim' while another "
            + "action is still in progress. This never affects the currently active procedure and never executes anything.")
    public ProcedureToolResult discardDeferredIntent() {
        session.markToolInvoked();
        return ProcedureToolResultMapper.toToolResult(ProcedureToolResultMapper.PROCEDURE_CONTROL,
                procedureCoordinator.discardDeferredIntent(session));
    }
}
