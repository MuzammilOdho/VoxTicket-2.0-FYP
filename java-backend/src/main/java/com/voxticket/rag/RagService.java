package com.voxticket.rag;

import com.voxticket.config.TimeoutProperties;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.TurnHandle;
import com.voxticket.observability.TurnMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final TimeoutProperties timeouts;

    /**
     * P0.1: bounds one RAG round-trip (Ollama embedding + pgvector search).
     * A stuck retrieval must degrade to "no policy context", never hang the
     * turn. Daemon threads; the pool exists only to allow timed waiting.
     */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "voxticket-rag-search-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

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

    public RagService(VectorStore vectorStore, RagProperties properties, TurnMetrics turnMetrics, TimeoutProperties timeouts) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.turnMetrics = turnMetrics;
        this.timeouts = timeouts;
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
        return searchDetailed(query, null);
    }

    /**
     * P1.2: ownership-aware variant. The handle is explicit (not via
     * {@code TurnContext}) because this runs on a pool thread inside the
     * tool's timeout future, where the turn's ThreadLocal is unavailable.
     * A preempted turn's retrieval is deferred before submitting, and its
     * in-flight future is registered for preemption cancellation.
     */
    DetailedSearch searchDetailed(String query, TurnHandle handle) {
        SearchOutcome outcome = searchInternal(query, handle);
        return new DetailedSearch(new RagSearchResult(outcome.hits(), outcome.cacheHit(), outcome.durationMs(), outcome.skipped()), outcome.snippets());
    }

    private record SearchOutcome(List<DocHit> hits, List<PolicySnippet> snippets, boolean cacheHit, long durationMs, boolean skipped) {
    }

    private SearchOutcome searchInternal(String query, TurnHandle handle) {
        String cacheKey = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        CachedSearch hit = searchCache.get(cacheKey);
        if (hit != null && hit.expiresAtMillis() > System.currentTimeMillis()) {
            turnMetrics.recordRagSearch(Duration.ZERO, hit.snippets().size());
            log.debug("event=rag_search_cached retrievedCount={} queryLength={}", hit.snippets().size(), query == null ? 0 : query.length());
            return new SearchOutcome(hit.hits(), hit.snippets(), true, 0, false);
        }

        // P1.2: never start retrieval for a dead turn.
        if (handle != null) {
            handle.checkOwned();
        }
        long start = System.nanoTime();
        // The instruction prefix is for the embedding model only - the raw
        // user query is what gets logged and audited.
        String retrievalQuery = QUERY_INSTRUCTION + (query == null ? "" : query);
        // P0.1: the embedding + vector-search round-trip runs under a hard
        // budget. On expiry the search is skipped and the turn continues
        // without policy context (degrade, never stall) - the skip is
        // recorded on the turn trace via RagSearchResult.skipped.
        List<Document> results;
        boolean skipped = false;
        Future<List<Document>> future = SEARCH_EXECUTOR.submit(() -> vectorStore.similaritySearch(
                SearchRequest.builder().query(retrievalQuery).topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build()));
        // P1.2: preemption cancels this future; the holder's next checkpoint
        // aborts the turn.
        if (handle != null) {
            handle.registerWork(future);
        }
        try {
            results = future.get(timeouts.ragTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (CancellationException e) {
            // P1.1: preemption cancelled the retrieval - an abort, not a
            // RAG failure. Never the degraded no-context path.
            log.info("event=rag_search_cancelled queryLength={}", query == null ? 0 : query.length());
            throw new TurnAbortedException(
                    new IllegalStateException("RAG search cancelled: turn superseded"));
        } catch (TimeoutException e) {
            // Interrupt the stuck retrieval; the turn degrades to no-context.
            future.cancel(true);
            skipped = true;
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("event=rag_search_timeout budgetMs={} queryLength={}", timeouts.ragTimeout().toMillis(), query == null ? 0 : query.length());
            turnMetrics.recordRagSearch(Duration.ofMillis(durationMs), 0);
            return new SearchOutcome(List.of(), List.of(), false, durationMs, true);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("RAG search interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("RAG search failed", e);
        } finally {
            if (handle != null) {
                handle.unregisterWork(future);
            }
        }
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
        return new SearchOutcome(hits, snippets, false, durationMs, skipped);
    }

    /** P2: one retrieved document - identity + relevance only, never content. */
    public record DocHit(String docId, String category, double similarity) {
    }

    /** P2: detailed RAG result for the turn decision trace. {@code skipped} is P0.1: the search exceeded its time budget and was skipped. */
    public record RagSearchResult(List<DocHit> hits, boolean cacheHit, long durationMs, boolean skipped) {
    }

    public record PolicySnippet(String category, String text) {
    }
}