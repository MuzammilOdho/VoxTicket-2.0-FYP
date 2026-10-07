package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.observability.TurnMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * P2: the detailed RAG search captures per-document id/category/similarity
 * for the turn decision trace, reports cache hits honestly, and never
 * exposes document text through the detailed result.
 */
class RagServiceDetailedTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RagService ragService = new RagService(vectorStore, new RagProperties(3, 0.5), mock(TurnMetrics.class));

    private static Document scoredDoc(String id, String category, double score) {
        return Document.builder().id(id).text("text for " + id).metadata("category", category).score(score).build();
    }

    @Test
    void detailedSearchCapturesDocIdCategoryAndSimilarity() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(
                        scoredDoc("doc-1", "returns-policy", 0.87),
                        scoredDoc("doc-2", "shipping", 0.62)));

        RagService.RagSearchResult result = ragService.searchPolicyDetailed("return window?");

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).docId()).isEqualTo("doc-1");
        assertThat(result.hits().get(0).category()).isEqualTo("returns-policy");
        assertThat(result.hits().get(0).similarity()).isEqualTo(0.87);
        assertThat(result.hits().get(1).docId()).isEqualTo("doc-2");
        assertThat(result.hits().get(1).similarity()).isEqualTo(0.62);
    }

    @Test
    void secondIdenticalQueryIsACacheHitWithZeroDuration() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(scoredDoc("doc-1", "returns-policy", 0.87)));

        RagService.RagSearchResult first = ragService.searchPolicyDetailed("How do returns work?");
        RagService.RagSearchResult second = ragService.searchPolicyDetailed("How do returns work?");

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.durationMs()).isZero();
        // Cached hits keep their identity and similarity.
        assertThat(second.hits()).hasSize(1);
        assertThat(second.hits().get(0).docId()).isEqualTo("doc-1");
        assertThat(second.hits().get(0).similarity()).isEqualTo(0.87);
    }

    @Test
    void detailedResultHasNoTextComponentByConstruction() {
        assertThat(RagService.DocHit.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("docId", "category", "similarity");
    }

    @Test
    void classicSearchPolicyStillReturnsSnippetsWithTextForTheModel() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(scoredDoc("doc-3", "claims", 0.9)));

        List<RagService.PolicySnippet> snippets = ragService.searchPolicy("damaged item?");

        assertThat(snippets).hasSize(1);
        assertThat(snippets.get(0).category()).isEqualTo("claims");
        assertThat(snippets.get(0).text()).isEqualTo("text for doc-3");
    }
}
