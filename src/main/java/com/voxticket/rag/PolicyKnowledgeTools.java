package com.voxticket.rag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class PolicyKnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(PolicyKnowledgeTools.class);

    private final RagService ragService;

    public PolicyKnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(description = "Search company policy documentation for questions about returns, refund timing, cancellation policy, "
            + "shipping, delivery, payment issues, claims (damaged/wrong/missing items), or human escalation. "
            + "Use this for general 'how does X work' policy questions - never to check whether a SPECIFIC order is eligible "
            + "for something, which the checkCancellationEligibility/checkReturnEligibility tools handle instead.")
    public Object searchPolicy(@ToolParam(description = "A natural-language question about company policy") String query) {
        long start = System.nanoTime();
        List<RagService.PolicySnippet> results = ragService.searchPolicy(query);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("event=tool_call tool=searchPolicy durationMs={} result=OK resultCount={}", durationMs, results.size());
        return results;
    }
}