package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.observability.TurnMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Regression test for the RAG search-cache eviction fix.
 *
 * <p>Previously the cache stopped accepting new entries forever once it
 * reached 200 distinct queries, because nothing ever evicted expired
 * entries. Now the oldest inserted entry is evicted while the cache is
 * full, so the 201st distinct query evicts the 1st, and re-searching the
 * 1st query is a cache miss (a fresh vector-store call).
 */
class RagServiceCacheEvictionTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RagService ragService = new RagService(vectorStore, new RagProperties(3, 0.5), mock(TurnMetrics.class));

    @Test
    void cacheEvictsOldestEntryWhenFullInsteadOfStopping() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        for (int i = 0; i < 200; i++) {
            ragService.searchPolicy("eviction query " + i);
        }
        // 201st distinct query: must evict "eviction query 0".
        ragService.searchPolicy("eviction query 200");
        // Re-searching the evicted query must miss and call the store again.
        ragService.searchPolicy("eviction query 0");
        // The most recent query must still be cached: no extra call.
        ragService.searchPolicy("eviction query 200");

        verify(vectorStore, times(202)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void recentQueriesStayCached() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ragService.searchPolicy("same question");
        ragService.searchPolicy("same question");
        ragService.searchPolicy("same question");

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        assertThat(ragService.searchPolicy("same question")).isEmpty();
    }
}
