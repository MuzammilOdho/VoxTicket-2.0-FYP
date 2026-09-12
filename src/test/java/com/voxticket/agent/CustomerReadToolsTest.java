package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.safety.ToolError;
import com.voxticket.service.CustomerOrderQueryService;
import com.voxticket.service.dto.OrderSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReadToolsTest {

    private final CustomerOrderQueryService queryService = mock(CustomerOrderQueryService.class);
    private final CustomerIdentity identity = new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567");
    private final CustomerReadTools tools = new CustomerReadTools(queryService, identity, mock(TurnMetrics.class));

    @Test
    void getMyOrderSummaryReturnsTheServiceResultOnSuccess() {
        OrderSummaryView summary = new OrderSummaryView(
                "ORD-10001", OrderStatus.OPEN, FulfillmentStatus.UNFULFILLED, "PKR", BigDecimal.TEN, Instant.now(), List.of());
        when(queryService.getOrderSummary(identity, "ORD-10001")).thenReturn(summary);

        Object result = tools.getMyOrderSummary("ORD-10001");

        assertThat(result).isSameAs(summary);
    }

    @Test
    void getMyOrderSummaryConvertsNotFoundIntoAStructuredToolError() {
        when(queryService.getOrderSummary(identity, "ORD-99999")).thenThrow(new ResourceNotFoundForAccountException("ORDER", "ORD-99999"));

        Object result = tools.getMyOrderSummary("ORD-99999");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }

    @Test
    void toolsConvertInsufficientAssuranceIntoAStructuredToolError() {
        when(queryService.getOrderSummary(identity, "ORD-10001"))
                .thenThrow(new InsufficientAssuranceException(IdentityAssurance.PHONE_MATCHED, IdentityAssurance.ANONYMOUS));

        Object result = tools.getMyOrderSummary("ORD-10001");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("IDENTITY_NOT_VERIFIED");
    }

    @Test
    void getMyPaymentStatusReturnsAToolErrorWhenNoPaymentExists() {
        when(queryService.getPaymentStatus(identity, "ORD-10001")).thenReturn(Optional.empty());

        Object result = tools.getMyPaymentStatus("ORD-10001");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NO_PAYMENT_RECORD");
    }

    @Test
    void getMyRefundStatusReturnsAToolErrorWhenNoRefundExists() {
        when(queryService.getLatestRefund(identity, "ORD-10001")).thenReturn(Optional.empty());

        Object result = tools.getMyRefundStatus("ORD-10001");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NO_REFUND_RECORD");
    }

    @Test
    void anInjectionStyledOrderReferenceIsHandledAsAnOrdinaryNonexistentReference() {
        String injectionAttempt = "'; DROP TABLE orders; --";
        when(queryService.getOrderSummary(identity, injectionAttempt))
                .thenThrow(new ResourceNotFoundForAccountException("ORDER", injectionAttempt));

        Object result = tools.getMyOrderSummary(injectionAttempt);

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }

    @Test
    void getMyOrderContextReturnsTheAggregatedServiceResultOnSuccess() {
        var context = new com.voxticket.service.dto.OrderContextView(
                "ORD-10001", "in progress", "not yet shipped", "PKR", BigDecimal.valueOf(1000), Instant.now(),
                List.of(), null, List.of(), "Eligible for cancellation.", List.of(), List.of(), List.of());
        when(queryService.getOrderContext(identity, "ORD-10001")).thenReturn(context);

        Object result = tools.getMyOrderContext("ORD-10001");

        assertThat(result).isSameAs(context);
    }
}