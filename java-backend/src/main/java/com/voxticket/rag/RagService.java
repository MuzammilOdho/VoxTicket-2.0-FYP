package com.voxticket.rag;

import com.voxticket.observability.TurnMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /**
     * Phase 8: bge-m3 retrieval instruction, prepended to the query ONLY for
     * the embedding call. Mirrors what the routing path already does with
     * the E5 {@code query:} prefix - query and document embeddings are
     * asymmetric without it. Logging and audit still use the original query.
     */
    static final String QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: ";

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final TurnMetrics turnMetrics;

    /**
     * Latency: every policy question paid a remote embedding + vector-search
     * round-trip, even repeats of the same question. Policies change rarely,
     * so successful searches are cached briefly. Keyed on the normalized raw
     * query (the instruction prefix is embedding-only and constant).
     */
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(10);
    private static final int SEARCH_CACHE_MAX_ENTRIES = 200;
    private final ConcurrentHashMap<String, CachedSearch> searchCache = new ConcurrentHashMap<>();

    private record CachedSearch(List<DocHit> hits, List<PolicySnippet> snippets, long expiresAtMillis) {
    }

    public RagService(VectorStore vectorStore, RagProperties properties, TurnMetrics turnMetrics) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.turnMetrics = turnMetrics;
    }

    public List<PolicySnippet> searchPolicy(String query) {
        return searchDetailed(query).snippets();
    }

    /**
     * P2: detailed search for the turn decision trace. Carries per-document
     * identity and similarity but never document text.
     */
    public RagSearchResult searchPolicyDetailed(String query) {
        return searchDetailed(query).result();
    }

    /**
     * Combined search used by {@link PolicyKnowledgeTools}: one vector-store
     * round-trip yielding both the model-facing snippets and the trace-safe
     * detailed result. Package-private - the text must never reach the trace.
     */
    record DetailedSearch(RagSearchResult result, List<PolicySnippet> snippets) {
    }

    DetailedSearch searchDetailed(String query) {
        SearchOutcome outcome = searchInternal(query);
        return new DetailedSearch(new RagSearchResult(outcome.hits(), outcome.cacheHit(), outcome.durationMs()), outcome.snippets());
    }

    private record SearchOutcome(List<DocHit> hits, List<PolicySnippet> snippets, boolean cacheHit, long durationMs) {
    }

    private SearchOutcome searchInternal(String query) {
        String cacheKey = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        CachedSearch hit = searchCache.get(cacheKey);
        if (hit != null && hit.expiresAtMillis() > System.currentTimeMillis()) {
            turnMetrics.recordRagSearch(Duration.ZERO, hit.snippets().size());
            log.debug("event=rag_search_cached retrievedCount={} queryLength={}", hit.snippets().size(), query == null ? 0 : query.length());
            return new SearchOutcome(hit.hits(), hit.snippets(), true, 0);
        }

        long start = System.nanoTime();
        // The instruction prefix is for the embedding model only - the raw
        // user query is what gets logged and audited.
        String retrievalQuery = QUERY_INSTRUCTION + (query == null ? "" : query);
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(retrievalQuery).topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build());
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        List<DocHit> hits = results.stream()
                .map(doc -> new DocHit(
                        doc.getId() == null ? "unknown" : doc.getId(),
                        String.valueOf(doc.getMetadata().getOrDefault("category", "policy")),
                        // The pgvector store always sets a score; 0.0 is the
                        // honest fallback when it does not - never fabricate.
                        doc.getScore() == null ? 0.0 : doc.getScore()))
                .toList();
        List<PolicySnippet> snippets = results.stream()
                .map(doc -> new PolicySnippet(String.valueOf(doc.getMetadata().getOrDefault("category", "policy")), doc.getText()))
                .toList();

        String categories = snippets.stream().map(PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
        log.info("event=rag_search topK={} similarityThreshold={} retrievedCount={} categories={} queryLength={} durationMs={}",
                properties.topK(), properties.similarityThreshold(), snippets.size(), categories, query == null ? 0 : query.length(), durationMs);
        turnMetrics.recordRagSearch(Duration.ofMillis(durationMs), snippets.size());

        if (searchCache.size() < SEARCH_CACHE_MAX_ENTRIES) {
            searchCache.put(cacheKey, new CachedSearch(hits, snippets, System.currentTimeMillis() + SEARCH_CACHE_TTL.toMillis()));
        }
        return new SearchOutcome(hits, snippets, false, durationMs);
    }

    /** P2: one retrieved document - identity + relevance only, never content. */
    public record DocHit(String docId, String category, double similarity) {
    }

    /** P2: detailed RAG result for the turn decision trace. */
    public record RagSearchResult(List<DocHit> hits, boolean cacheHit, long durationMs) {
    }

    public record PolicySnippet(String category, String text) {
    }
}