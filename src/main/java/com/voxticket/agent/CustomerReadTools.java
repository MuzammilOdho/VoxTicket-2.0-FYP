package com.voxticket.agent;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.safety.ToolError;
import com.voxticket.service.CustomerOrderQueryService;
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
    private final TurnMetrics turnMetrics;

    public CustomerReadTools(CustomerOrderQueryService queryService, CustomerIdentity identity, TurnMetrics turnMetrics) {
        this.queryService = queryService;
        this.identity = identity;
        this.turnMetrics = turnMetrics;
    }

    @Tool(description = "Get the customer's most recent orders, most recent first.")
    public Object getMyRecentOrders() {
        return safely("getMyRecentOrders", null, () -> queryService.getRecentOrders(identity, 5));
    }

    @Tool(description = "Get a summary of one of the customer's own orders by order number, including its items.")
    public Object getMyOrderSummary(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyOrderSummary", orderReference, () -> queryService.getOrderSummary(identity, orderReference));
    }

    @Tool(description = "Get shipment/tracking status for one of the customer's own orders.")
    public Object getMyShipmentStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyShipmentStatus", orderReference, () -> queryService.getShipmentStatus(identity, orderReference));
    }

    @Tool(description = "Get payment status for one of the customer's own orders.")
    public Object getMyPaymentStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyPaymentStatus", orderReference, () -> {
            var payment = queryService.getPaymentStatus(identity, orderReference);
            return payment.isPresent() ? payment.get() : new ToolError("NO_PAYMENT_RECORD", "No payment record was found for this order.");
        });
    }

    @Tool(description = "Get the most recent refund status for one of the customer's own orders.")
    public Object getMyRefundStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyRefundStatus", orderReference, () -> {
            var refund = queryService.getLatestRefund(identity, orderReference);
            return refund.isPresent() ? refund.get() : new ToolError("NO_REFUND_RECORD", "No refund has been initiated for this order.");
        });
    }

    @Tool(description = "Get return status/history for one of the customer's own orders.")
    public Object getMyReturnStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyReturnStatus", orderReference, () -> queryService.getReturnStatus(identity, orderReference));
    }

    @Tool(description = "Get the status of one of the customer's own support tickets.")
    public Object getMyTicketStatus(@ToolParam(description = "Ticket number, e.g. TCK-00001") String ticketReference) {
        return safely("getMyTicketStatus", ticketReference, () -> queryService.getTicketStatus(identity, ticketReference));
    }

    @Tool(description = "Check whether one of the customer's own orders is currently eligible for cancellation, and what would happen to the payment if it were cancelled.")
    public Object checkCancellationEligibility(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("checkCancellationEligibility", orderReference, () -> queryService.checkCancellationEligibility(identity, orderReference));
    }

    @Tool(description = "Check whether a specific item on one of the customer's own orders is currently eligible for return.")
    public Object checkReturnEligibility(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(description = "Item SKU, from the order summary") String itemReference) {
        return safely("checkReturnEligibility", orderReference + "/" + itemReference,
                () -> queryService.checkReturnEligibility(identity, orderReference, itemReference));
    }

    private Object safely(String toolName, String reference, Supplier<Object> call) {
        long start = System.nanoTime();
        try {
            Object result = call.get();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String resultCode = (result instanceof ToolError toolError) ? toolError.code() : "OK";
            log.info("event=tool_call tool={} reference={} durationMs={} result={}", toolName, safeReference(reference), durationMs, resultCode);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, resultCode);
            return result;
        } catch (ResourceNotFoundForAccountException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("event=tool_call tool={} reference={} durationMs={} result=NOT_FOUND_FOR_ACCOUNT", toolName, safeReference(reference), durationMs);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, "NOT_FOUND_FOR_ACCOUNT");
            return new ToolError(
                    "NOT_FOUND_FOR_ACCOUNT",
                    "That reference doesn't match any of the customer's own records. Do not guess or invent an "
                            + "order/ticket number - ask the customer to double check it, or use getMyRecentOrders to help them find the right one.");
        } catch (InsufficientAssuranceException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("event=tool_call tool={} reference={} durationMs={} result=IDENTITY_NOT_VERIFIED", toolName, safeReference(reference), durationMs);
            turnMetrics.recordToolCall(Duration.ofMillis(durationMs), toolName, "IDENTITY_NOT_VERIFIED");
            return new ToolError(
                    "IDENTITY_NOT_VERIFIED",
                    "The customer's phone number could not be matched to an account, so their own order data cannot be looked up yet.");
        }
    }

    @Tool(description = "Get a complete picture of one of the customer's own orders - status, items, payment, shipment, cancellation "
            + "eligibility, and any related returns, refunds, or claims - all in one call. Prefer this over the narrower single-purpose "
            + "tools (getMyShipmentStatus, getMyPaymentStatus, getMyRefundStatus, getMyReturnStatus) whenever you need more than one "
            + "piece of information about an order.")
    public Object getMyOrderContext(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely("getMyOrderContext", orderReference, () -> queryService.getOrderContext(identity, orderReference));
    }

    private String safeReference(String reference) {
        return reference == null ? "none" : reference;
    }
}