package com.voxticket.rag;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Spec §43 - the "unexpected policy requirement" path only
 * (SupportAgent → searchPolicy()). The prefetch path ("obvious policy
 * request" → ContextBuilder prefetch) is deliberately NOT implemented, per
 * the resolved decision: avoid a second serial model call just to decide
 * whether to prefetch, and only add that optimization later if measured
 * latency justifies it.
 *
 * <p>Spec §44: this explains policy in natural language. It is never the
 * source of truth for whether a SPECIFIC order/item is eligible for
 * anything - that's Phase 3's CancellationPolicyService/ReturnPolicyService,
 * exposed via Phase 5's eligibility tools.
 */
@Service
public class RagService {

    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<PolicySnippet> searchPolicy(String query) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(TOP_K).similarityThreshold(SIMILARITY_THRESHOLD).build());
        return results.stream()
                .map(doc -> new PolicySnippet(String.valueOf(doc.getMetadata().getOrDefault("category", "policy")), doc.getText()))
                .toList();
    }

    public record PolicySnippet(String category, String text) {
    }
}