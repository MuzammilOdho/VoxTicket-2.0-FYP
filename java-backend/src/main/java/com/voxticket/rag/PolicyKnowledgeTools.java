package com.voxticket.rag;

import com.voxticket.audit.ConversationAuditService;
import com.voxticket.agent.ToolCallTimeout;
import com.voxticket.agent.ToolSideEffect;
import com.voxticket.config.TimeoutProperties;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.TurnContext;
import com.voxticket.conversation.TurnHandle;
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
    private final TimeoutProperties timeouts;

    public PolicyKnowledgeTools(RagService ragService, TurnMetrics turnMetrics, ConversationSession session, ConversationAuditService auditService,
            TimeoutProperties timeouts) {
        this.ragService = ragService;
        this.turnMetrics = turnMetrics;
        this.session = session;
        this.auditService = auditService;
        this.timeouts = timeouts;
    }

    @Tool(description = "Search authoritative store policy. Use for general questions about how cancellation, "
            + "returns, refunds, shipping, delivery, payments, or claims work. Do not use to determine "
            + "eligibility or current state of a specific order. Do not use when the customer wants to reach "
            + "a human now - that is requestHumanSupport, not a policy lookup.")
    public Object searchPolicy(@ToolParam(description = "A natural-language question about company policy") String query) {
        session.markToolInvoked();
        long start = System.nanoTime();
        try {
            // P0.1: the whole tool body (including the RAG round-trip, which has
            // its own budget inside RagService) is bounded - a stuck call becomes
            // a ToolTimeoutException -> sanitized TOOL_EXECUTION_FAILED envelope.
            // P1.2: the turn's handle is captured on the servlet thread (the
            // lambda below runs on a pool thread where TurnContext is
            // unavailable); the defer/cancel semantics also come from
            // ToolCallTimeout via TurnContext.
            TurnHandle handle = TurnContext.current();
            return ToolCallTimeout.call("searchPolicy", timeouts.toolTimeout(), () -> {
                // P2: one search yields both the model-facing snippets and the
                // trace-safe detailed result - no second round-trip, no text leakage
                // into the trace.
                RagService.DetailedSearch detailed = ragService.searchDetailed(query, handle);
                List<RagService.PolicySnippet> results = detailed.snippets();
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.info("event=tool_call tool=searchPolicy durationMs={} result=OK resultCount={}", durationMs, results.size());
                turnMetrics.recordToolCall(Duration.ofMillis(durationMs), "searchPolicy", "OK");
                traceRagSearch(detailed.result(), durationMs);
                String categories = results.stream().map(RagService.PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
                auditService.recordEvent(session, session.getTurnCount(), ConversationEventType.RAG_SEARCH,
                        "resultCount=" + results.size() + " categories=" + categories + " durationMs=" + durationMs);
                return results;
            });
        } catch (TurnAbortedException aborted) {
            // P1.2: preempted turn - mark cancelled and rethrow; never the
            // sanitized path.
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            TurnTrace.Builder builder = session.getActiveTraceBuilder();
            if (builder != null) {
                builder.addToolCall("searchPolicy", "CANCELLED", (double) durationMs, ToolSideEffect.READ_ONLY);
            }
            throw aborted;
        }
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
        // P0.1: a timed-out search degrades to no-context - the skip is
        // recorded so the degradation is visible on the trace.
        builder.ragSkipped(result.skipped());
        for (RagService.DocHit hit : result.hits()) {
            builder.addRagDoc(hit.docId(), hit.category(), hit.similarity());
        }
        builder.addToolCall("searchPolicy", "OK", (double) toolDurationMs, ToolSideEffect.READ_ONLY);
    }
}