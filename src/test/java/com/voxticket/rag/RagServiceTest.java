package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RagServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RagService ragService = new RagService(vectorStore, 3, 0.5);

    @Test
    void mapsRetrievedDocumentsToPolicySnippetsWithCategoryAndText() {
        Document doc = new Document("Return window: 30 days.", Map.of("category", "returns-policy"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<RagService.PolicySnippet> results = ragService.searchPolicy("how long do I have to return something");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).category()).isEqualTo("returns-policy");
        assertThat(results.get(0).text()).isEqualTo("Return window: 30 days.");
    }

    @Test
    void missingCategoryMetadataFallsBackToAGenericLabel() {
        Document doc = new Document("Some policy text.", Map.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        assertThat(ragService.searchPolicy("test query").get(0).category()).isEqualTo("policy");
    }

    @Test
    void noMatchingDocumentsReturnsAnEmptyList() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertThat(ragService.searchPolicy("something totally unrelated")).isEmpty();
    }
}