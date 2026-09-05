package com.voxticket.rag;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;

    public RagService(
            VectorStore vectorStore,
            @Value("${voxticket.rag.top-k:3}") int topK,
            @Value("${voxticket.rag.similarity-threshold:0.5}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public List<PolicySnippet> searchPolicy(String query) {
        long start = System.nanoTime();
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).similarityThreshold(similarityThreshold).build());
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        List<PolicySnippet> snippets = results.stream()
                .map(doc -> new PolicySnippet(String.valueOf(doc.getMetadata().getOrDefault("category", "policy")), doc.getText()))
                .toList();

        String categories = snippets.stream().map(PolicySnippet::category).distinct().reduce((a, b) -> a + "," + b).orElse("none");
        log.info("event=rag_search topK={} similarityThreshold={} retrievedCount={} categories={} queryLength={} durationMs={}",
                topK, similarityThreshold, snippets.size(), categories, query == null ? 0 : query.length(), durationMs);

        return snippets;
    }

    public record PolicySnippet(String category, String text) {
    }
}