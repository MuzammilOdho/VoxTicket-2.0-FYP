package com.voxticket.agent;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.safety.ToolError;
import com.voxticket.service.CustomerOrderQueryService;
import com.voxticket.service.dto.OrderSupportContext;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class CustomerReadTools {

    private static final Logger log = LoggerFactory.getLogger(CustomerReadTools.class);

    private final CustomerOrderQueryService queryService;
    private final CustomerIdentity identity;
    private final ConversationSession session;
    private final TurnMetrics turnMetrics;
    private final ConversationAuditService auditService;

    public CustomerReadTools(
            CustomerOrderQueryService queryService, CustomerIdentity identity, ConversationSession session,
            TurnMetrics turnMetrics, ConversationAuditService auditService) {
        this.queryService = queryService;
        this.identity = identity;
        this.session = session;
        this.turnMetrics = turnMetrics;
        this.auditService = auditService;
    }

    @Tool(description = "List the current customer's recent orders, newest first. Use when the customer does not know "
            + "the order reference or when several recent orders must be distinguished. Do not use when a specific "
            + "order reference is already known.")
    public Object getMyRecentOrders() {
        return safely("getMyRecentOrders", null, () -> queryService.getRecentOrders(identity, 5));
    }

    @Tool(description = "Get the authoritative support context for one order owned by the current customer. This is "
            + "the single complete support-state read: use it for questions about that order's status, items, payment, "
            + "shipping, tracking, cancellation availability, per-item return and claim availability, refunds, "
            + "returns, or claims. Capability fields are computed deterministically by the application - do not "
            + "suggest a support action as available unless the returned capability data establishes it. Do not use "
            + "for general store-policy questions. The application enforces customer ownership.")
    public Object getMyOrderContext(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        Object result = safely("getMyOrderContext", orderReference, () -> queryService.getOrderSupportContext(identity, orderReference));
        if (result instanceof OrderSupportContext context) {
            session.recordFocusOrder(context.orderNumber());
        }
        return result;
    }

    @Tool(description = "Get the authoritative status of one support ticket owned by the current customer. Use when "
            + "the customer asks about a specific support-ticket reference.")
    public Object getMyTicketStatus(@ToolParam(description = "Ticket number, e.g. TCK-00001") String ticketReference) {
        return safely("getMyTicketStatus", ticketReference, () -> queryService.getTicketStatus(identity, ticketReference));
    }

    private Object safely(String toolName, String reference, Supplier<Object> call) {
        session.markToolInvoked();
        long start = System.nanoTime();
        try {
            Object result = call.get();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String resultCode = (result instanceof ToolError toolError) ? toolError.code() : "OK";
            log.info("event=tool_call tool={} reference={} durationMs={} result={}", toolName, safeReference(reference), durationMs, resultCode);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, resultCode);
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.TOOL_CALLED,
                    "tool=" + toolName + " result=" + resultCode + " durationMs=" + durationMs);
            return result;
        } catch (ResourceNotFoundForAccountException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("event=tool_call tool={} reference={} durationMs={} result=NOT_FOUND_FOR_ACCOUNT", toolName, safeReference(reference), durationMs);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, "NOT_FOUND_FOR_ACCOUNT");
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.TOOL_CALLED,
                    "tool=" + toolName + " result=NOT_FOUND_FOR_ACCOUNT durationMs=" + durationMs);
            return new ToolError(
                    "NOT_FOUND_FOR_ACCOUNT",
                    "That reference doesn't match any of the customer's own records. Do not guess or invent an "
                            + "order/ticket number - ask the customer to double check it, or use getMyRecentOrders to help them find the right one.");
        } catch (InsufficientAssuranceException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("event=tool_call tool={} reference={} durationMs={} result=IDENTITY_NOT_VERIFIED", toolName, safeReference(reference), durationMs);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, "IDENTITY_NOT_VERIFIED");
            auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.TOOL_CALLED,
                    "tool=" + toolName + " result=IDENTITY_NOT_VERIFIED durationMs=" + durationMs);
            return new ToolError(
                    "IDENTITY_NOT_VERIFIED",
                    "The customer's phone number could not be matched to an account, so their own order data cannot be looked up yet.");
        }
    }

    private String safeReference(String reference) {
        return reference == null ? "none" : reference;
    }
}