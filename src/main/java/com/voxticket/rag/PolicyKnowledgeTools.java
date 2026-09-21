package com.voxticket.rag;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Phase 8: now constructed per-turn (like CustomerReadTools/
 * ProcedureRequestTools) rather than as a singleton bean, since it needs
 * session context to record RAG_SEARCH audit events. No longer @Component.
 */
public class PolicyKnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(PolicyKnowledgeTools.class);

    private final RagService ragService;
    private final TurnMetrics turnMetrics;
    private final ConversationSession session;
    private final ConversationAuditService auditService;

    public PolicyKnowledgeTools(RagService ragService, TurnMetrics turnMetrics, ConversationSession session, ConversationAuditService auditService) {
        this.ragService = ragService;
        this.turnMetrics = turnMetrics;
        this.session = session;
        this.auditService = auditService;
    }

    @Tool(description = "Search company policy documentation for questions about returns, refund timing, cancellation policy, "
            + "shipping, delivery, payment issues, claims (damaged/wrong/missing items), or human escalation. "
            + "Use this for general 'how does X work' policy questions - never to check whether a SPECIFIC order is eligible "
            + "for something; getMyOrderContext already tells you cancellation eligibility, and requestReturn reports return "
            + "eligibility itself if it isn't eligible.")
    public Object searchPolicy(@ToolParam(description = "A natural-language question about company policy") String query) {
        session.markToolInvoked();
        long start = System.nanoTime();
        List<RagService.PolicySnippet> results = ragService.searchPolicy(query);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("event=tool_call tool=searchPolicy durationMs={} result=OK resultCount={}", durationMs, results.size());
        turnMetrics.recordToolCall(Duration.ofMillis(durationMs), "searchPolicy", "OK");
        String categories = results.stream().map(RagService.PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
        auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.RAG_SEARCH,
                "resultCount=" + results.size() + " categories=" + categories + " durationMs=" + durationMs);
        return results;
    }
}