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
import com.voxticket.service.dto.OrderContextView;
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

    private OrderContextView sampleContext() {
        return new OrderContextView(
                "ORD-10001", "in progress", "not yet shipped", "PKR", BigDecimal.valueOf(1000), Instant.now(),
                List.of(), null, List.of(), "Eligible for cancellation.", List.of(), List.of(), List.of());
    }

    @Test
    void getMyOrderContextReturnsTheAggregatedServiceResultOnSuccess() {
        OrderContextView context = sampleContext();
        when(queryService.getOrderContext(identity, "ORD-10001")).thenReturn(context);

        Object result = tools.getMyOrderContext("ORD-10001");

        assertThat(result).isSameAs(context);
    }

    @Test
    void getMyOrderContextConvertsNotFoundIntoAStructuredToolError() {
        when(queryService.getOrderContext(identity, "ORD-99999")).thenThrow(new ResourceNotFoundForAccountException("ORDER", "ORD-99999"));

        Object result = tools.getMyOrderContext("ORD-99999");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }

    @Test
    void toolsConvertInsufficientAssuranceIntoAStructuredToolError() {
        when(queryService.getOrderContext(identity, "ORD-10001"))
                .thenThrow(new InsufficientAssuranceException(IdentityAssurance.PHONE_MATCHED, IdentityAssurance.ANONYMOUS));

        Object result = tools.getMyOrderContext("ORD-10001");

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("IDENTITY_NOT_VERIFIED");
    }

    @Test
    void anInjectionStyledOrderReferenceIsHandledAsAnOrdinaryNonexistentReference() {
        String injectionAttempt = "'; DROP TABLE orders; --";
        when(queryService.getOrderContext(identity, injectionAttempt))
                .thenThrow(new ResourceNotFoundForAccountException("ORDER", injectionAttempt));

        Object result = tools.getMyOrderContext(injectionAttempt);

        assertThat(result).isInstanceOf(ToolError.class);
        assertThat(((ToolError) result).code()).isEqualTo("NOT_FOUND_FOR_ACCOUNT");
    }
}