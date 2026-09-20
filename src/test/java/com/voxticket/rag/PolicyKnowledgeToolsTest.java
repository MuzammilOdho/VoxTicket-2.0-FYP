package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyKnowledgeToolsTest {

    private final RagService ragService = mock(RagService.class);
    private final ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
    private final PolicyKnowledgeTools tools = new PolicyKnowledgeTools(ragService, mock(TurnMetrics.class), session, mock(ConversationAuditService.class));

    @Test
    void delegatesDirectlyToRagService() {
        List<RagService.PolicySnippet> expected = List.of(new RagService.PolicySnippet("refund-timing", "Settlement time: a few business days."));
        when(ragService.searchPolicy("how long do refunds take")).thenReturn(expected);

        Object result = tools.searchPolicy("how long do refunds take");

        assertThat(result).isSameAs(expected);
    }
}