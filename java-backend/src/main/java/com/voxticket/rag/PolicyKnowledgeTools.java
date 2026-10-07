package com.voxticket.rag;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.observability.TurnTrace;
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

    @Tool(description = "Search authoritative store policy. Use for general questions about how cancellation, "
            + "returns, refunds, shipping, delivery, payments, or claims work. Do not use to determine "
            + "eligibility or current state of a specific order. Do not use when the customer wants to reach "
            + "a human now - that is requestHumanSupport, not a policy lookup.")
    public Object searchPolicy(@ToolParam(description = "A natural-language question about company policy") String query) {
        session.markToolInvoked();
        long start = System.nanoTime();
        // P2: one search yields both the model-facing snippets and the
        // trace-safe detailed result - no second round-trip, no text leakage
        // into the trace.
        RagService.DetailedSearch detailed = ragService.searchDetailed(query);
        List<RagService.PolicySnippet> results = detailed.snippets();
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        log.info("event=tool_call tool=searchPolicy durationMs={} result=OK resultCount={}", durationMs, results.size());
        turnMetrics.recordToolCall(Duration.ofMillis(durationMs), "searchPolicy", "OK");
        traceRagSearch(detailed.result(), durationMs);
        String categories = results.stream().map(RagService.PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
        auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.RAG_SEARCH,
                "resultCount=" + results.size() + " categories=" + categories + " durationMs=" + durationMs);
        return results;
    }

    /**
     * P2: records the RAG search on the turn's trace builder. Only document
     * id/category/similarity are recorded - never document text.
     */
    private void traceRagSearch(RagService.RagSearchResult result, long toolDurationMs) {
        TurnTrace.Builder builder = session.getActiveTraceBuilder();
        if (builder == null) {
            return;
        }
        builder.ragMs((double) result.durationMs());
        builder.ragCacheHit(result.cacheHit());
        for (RagService.DocHit hit : result.hits()) {
            builder.addRagDoc(hit.docId(), hit.category(), hit.similarity());
        }
        builder.addToolCall("searchPolicy", "OK", (double) toolDurationMs);
    }
}