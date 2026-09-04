package com.voxticket.agent;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.service.CustomerOrderQueryService;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spec §18. AI-facing read tools.
 *
 * <p><b>Deliberately NOT a Spring-managed singleton bean.</b> A fresh
 * instance is constructed for every single turn (by {@link SupportAgent}),
 * closed over the {@link CustomerIdentity} resolved for THAT turn's
 * session. This is the structural guarantee behind "the LLM must never
 * supply customerId" (spec §10): there is no parameter on any method here
 * through which a customerId could even be passed, and there is no shared,
 * reused instance that could carry one customer's identity into another
 * customer's request.
 *
 * <p>Every method is a thin pass-through to {@link CustomerOrderQueryService}
 * (spec §58: tools should be thin adapters over application services).
 * Authorization is enforced exactly once, in that service - never
 * duplicated here. Domain exceptions are caught and converted into a small
 * structured {@link ToolError} rather than allowed to propagate as a raw
 * exception through the tool-calling machinery, so the model always gets
 * something it can react to sensibly instead of a stack trace it might
 * paraphrase into something misleading.
 */
public class CustomerReadTools {

    private final CustomerOrderQueryService queryService;
    private final CustomerIdentity identity;

    public CustomerReadTools(CustomerOrderQueryService queryService, CustomerIdentity identity) {
        this.queryService = queryService;
        this.identity = identity;
    }

    @Tool(description = "Get the customer's most recent orders, most recent first.")
    public Object getMyRecentOrders() {
        return safely(() -> queryService.getRecentOrders(identity, 5));
    }

    @Tool(description = "Get a summary of one of the customer's own orders by order number, including its items.")
    public Object getMyOrderSummary(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> queryService.getOrderSummary(identity, orderReference));
    }

    @Tool(description = "Get shipment/tracking status for one of the customer's own orders.")
    public Object getMyShipmentStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> queryService.getShipmentStatus(identity, orderReference));
    }

    @Tool(description = "Get payment status for one of the customer's own orders.")
    public Object getMyPaymentStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> {
            var payment = queryService.getPaymentStatus(identity, orderReference);
            return payment.isPresent() ? payment.get() : new ToolError("NO_PAYMENT_RECORD", "No payment record was found for this order.");
        });
    }

    @Tool(description = "Get the most recent refund status for one of the customer's own orders.")
    public Object getMyRefundStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> {
            var refund = queryService.getLatestRefund(identity, orderReference);
            return refund.isPresent() ? refund.get() : new ToolError("NO_REFUND_RECORD", "No refund has been initiated for this order.");
        });
    }

    @Tool(description = "Get return status/history for one of the customer's own orders.")
    public Object getMyReturnStatus(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> queryService.getReturnStatus(identity, orderReference));
    }

    @Tool(description = "Get the status of one of the customer's own support tickets.")
    public Object getMyTicketStatus(@ToolParam(description = "Ticket number, e.g. TCK-00001") String ticketReference) {
        return safely(() -> queryService.getTicketStatus(identity, ticketReference));
    }

    @Tool(description = "Check whether one of the customer's own orders is currently eligible for cancellation, and what would happen to the payment if it were cancelled.")
    public Object checkCancellationEligibility(@ToolParam(description = "Order number, e.g. ORD-10001") String orderReference) {
        return safely(() -> queryService.checkCancellationEligibility(identity, orderReference));
    }

    @Tool(description = "Check whether a specific item on one of the customer's own orders is currently eligible for return.")
    public Object checkReturnEligibility(
            @ToolParam(description = "Order number, e.g. ORD-10001") String orderReference,
            @ToolParam(description = "Item SKU, from the order summary") String itemReference) {
        return safely(() -> queryService.checkReturnEligibility(identity, orderReference, itemReference));
    }

    private Object safely(Supplier<Object> call) {
        try {
            return call.get();
        } catch (ResourceNotFoundForAccountException e) {
            return new ToolError(
                    "NOT_FOUND_FOR_ACCOUNT",
                    "That reference doesn't match any of the customer's own records. Do not guess or invent an "
                            + "order/ticket number - ask the customer to double check it, or use getMyRecentOrders to help them find the right one.");
        } catch (InsufficientAssuranceException e) {
            return new ToolError(
                    "IDENTITY_NOT_VERIFIED",
                    "The customer's phone number could not be matched to an account, so their own order data cannot be looked up yet.");
        }
    }

    public record ToolError(String code, String message) {
    }
}