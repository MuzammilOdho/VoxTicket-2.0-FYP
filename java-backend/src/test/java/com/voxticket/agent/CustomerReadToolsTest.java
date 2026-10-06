package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.safety.ToolError;
import com.voxticket.service.CustomerOrderQueryService;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.service.dto.CancellationCapabilityView;
import com.voxticket.service.dto.ItemClaimCapabilityView;
import com.voxticket.service.dto.ItemReturnCapabilityView;
import com.voxticket.service.dto.OrderSupportContext;
import com.voxticket.service.dto.OrderSupportItemView;
import com.voxticket.service.dto.RefundStateView;
import com.voxticket.policy.ClaimCapabilityState;
import com.voxticket.service.dto.SupportCapabilitiesView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReadToolsTest {

    private final CustomerOrderQueryService queryService = mock(CustomerOrderQueryService.class);
    private final CustomerIdentity identity = new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567");
    private final ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
    private final CustomerReadTools tools = new CustomerReadTools(queryService, identity, session, mock(TurnMetrics.class), mock(ConversationAuditService.class));

    private OrderSupportContext sampleContext() {
        CancellationCapabilityView cancellation = new CancellationCapabilityView(true, null, "REFUND_REQUIRED");
        return new OrderSupportContext(
                "ORD-10001", OrderStatus.OPEN, FulfillmentStatus.UNFULFILLED, Instant.now(), "PKR", BigDecimal.valueOf(1000),
                List.of(new OrderSupportItemView("Cotton Bedsheet Set", 1, BigDecimal.valueOf(1000),
                        new ItemReturnCapabilityView(false, "ITEM_NOT_DELIVERED", 0),
                        new ItemClaimCapabilityView(ClaimCapabilityState.AVAILABLE, null, "MANUAL_REVIEW", null))),
                null, List.of(),
                cancellation,
                List.of(), List.of(), List.of(),
                new SupportCapabilitiesView(false, true, new RefundStateView(false, null, false, false)));
    }

    @Test
    void getMyOrderContextReturnsTheAggregatedServiceResultOnSuccess() {
        OrderSupportContext context = sampleContext();
        when(queryService.getOrderSupportContext(identity, "ORD-10001")).thenReturn(context);

        Object result = tools.getMyOrderContext("ORD-10001");

        assertThat(result).isSameAs(context);
    }

    @Test
    void getMyOrderContextConvertsNotFoundIntoAStructuredToolError() {
        when(queryService.getOrderSupportContext(identity, "ORD-99999")).thenThrow(new ResourceNotFoundForAccountException("ORDER", "ORD-99999"));

        Object result = tools.getMyOrderContext("ORD-99999");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }

    @Test
    void toolsConvertInsufficientAssuranceIntoAStructuredToolError() {
        when(queryService.getOrderSupportContext(identity, "ORD-10001"))
                .thenThrow(new InsufficientAssuranceException(IdentityAssurance.PHONE_MATCHED, IdentityAssurance.ANONYMOUS));

        Object result = tools.getMyOrderContext("ORD-10001");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("IDENTITY_NOT_VERIFIED");
    }

    @Test
    void getMyOrderContextRecordsTheFocusOrderInTheSession() {
        OrderSupportContext context = sampleContext();
        when(queryService.getOrderSupportContext(identity, "ORD-10001")).thenReturn(context);

        tools.getMyOrderContext("ORD-10001");

        assertThat(session.getFocus()).isPresent();
        assertThat(session.getFocus().orElseThrow().orderNumber()).isEqualTo("ORD-10001");
    }

    @Test
    void anInjectionStyledOrderReferenceIsHandledAsAnOrdinaryNonexistentReference() {
        String injectionAttempt = "'; DROP TABLE orders; --";
        when(queryService.getOrderSupportContext(identity, injectionAttempt))
                .thenThrow(new ResourceNotFoundForAccountException("ORDER", injectionAttempt));

        Object result = tools.getMyOrderContext(injectionAttempt);

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }
}