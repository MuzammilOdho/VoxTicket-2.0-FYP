package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.observability.TurnMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyKnowledgeToolsTest {

    private final RagService ragService = mock(RagService.class);
    private final PolicyKnowledgeTools tools = new PolicyKnowledgeTools(ragService, mock(TurnMetrics.class));

    @Test
    void delegatesDirectlyToRagService() {
        List<RagService.PolicySnippet> expected = List.of(new RagService.PolicySnippet("refund-timing", "Settlement time: a few business days."));
        when(ragService.searchPolicy("how long do refunds take")).thenReturn(expected);

        Object result = tools.searchPolicy("how long do refunds take");

        assertThat(result).isSameAs(expected);
    }
}