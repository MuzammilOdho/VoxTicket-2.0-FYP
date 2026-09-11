package com.voxticket.rag;

import com.voxticket.observability.TurnMetrics;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final TurnMetrics turnMetrics;

    public RagService(VectorStore vectorStore, RagProperties properties, TurnMetrics turnMetrics) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.turnMetrics = turnMetrics;
    }

    public List<PolicySnippet> searchPolicy(String query) {
        long start = System.nanoTime();
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build());
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        List<PolicySnippet> snippets = results.stream()
                .map(doc -> new PolicySnippet(String.valueOf(doc.getMetadata().getOrDefault("category", "policy")), doc.getText()))
                .toList();

        String categories = snippets.stream().map(PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
        log.info("event=rag_search topK={} similarityThreshold={} retrievedCount={} categories={} queryLength={} durationMs={}",
                properties.topK(), properties.similarityThreshold(), snippets.size(), categories, query == null ? 0 : query.length(), durationMs);
        turnMetrics.recordRagSearch(Duration.ofMillis(durationMs), snippets.size());

        return snippets;
    }

    public record PolicySnippet(String category, String text) {
    }
}