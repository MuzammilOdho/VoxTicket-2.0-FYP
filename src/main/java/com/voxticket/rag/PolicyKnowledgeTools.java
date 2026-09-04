package com.voxticket.rag;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spec §18. Unlike CustomerReadTools, this needs no customer identity at
 * all - policy content is the same for every caller - so it's a normal
 * Spring-managed singleton, safe to reuse across turns and customers.
 */
@Component
public class PolicyKnowledgeTools {

    private final RagService ragService;

    public PolicyKnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(description = "Search company policy documentation for questions about returns, refund timing, cancellation policy, "
            + "shipping, delivery, payment issues, claims (damaged/wrong/missing items), or human escalation. "
            + "Use this for general 'how does X work' policy questions - never to check whether a SPECIFIC order is eligible "
            + "for something, which the checkCancellationEligibility/checkReturnEligibility tools handle instead.")
    public Object searchPolicy(@ToolParam(description = "A natural-language question about company policy") String query) {
        return ragService.searchPolicy(query);
    }
}