package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.voxticket.embedding.EmbeddingProperties;
import com.voxticket.embedding.VoyageEmbeddingException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 8: KnowledgeIngestionService sync semantics - change detection,
 * managed-row scoping, and startup survival. No Docker/Ollama: the
 * VectorStore is mocked and the stored-hash lookup is stubbed by
 * subclassing, so only the service's own logic is exercised.
 */
class KnowledgeIngestionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private static final EmbeddingProperties LOCAL_PROPERTIES = new EmbeddingProperties(
            "local", new EmbeddingProperties.VoyageProperties("", "voyage-4-lite", "https://api.voyageai.com/v1", 5));
    private static final EmbeddingProperties VOYAGE_PROPERTIES = new EmbeddingProperties(
            "voyage", new EmbeddingProperties.VoyageProperties("test-key", "voyage-4-lite", "https://api.voyageai.com/v1", 5));
    private final KnowledgeIngestionService ingestionService =
            new KnowledgeIngestionService(mock(VectorStore.class), jdbcTemplate, LOCAL_PROPERTIES);

    /** A service whose stored-hash and stored-identity lookups are controlled by the test. */
    private KnowledgeIngestionService serviceWithStoredHashes(VectorStore store, Map<String, String> storedHashes) {
        return serviceWithStoredHashesAndIdentities(store, storedHashes, currentIdentities());
    }

    private KnowledgeIngestionService serviceWithStoredHashesAndIdentities(
            VectorStore store, Map<String, String> storedHashes, Map<String, String> storedIdentities) {
        return new KnowledgeIngestionService(store, jdbcTemplate, LOCAL_PROPERTIES) {
            @Override
            Map<String, String> loadStoredHashes() {
                return storedHashes;
            }

            @Override
            Map<String, String> loadStoredEmbeddingIdentities() {
                return storedIdentities;
            }
        };
    }

    private Map<String, String> currentIdentities() {
        try {
            return ingestionService.loadKnowledgeDocuments().stream().collect(Collectors.toMap(
                    Document::getId,
                    d -> String.valueOf(d.getMetadata().get(KnowledgeIngestionService.METADATA_EMBEDDING_IDENTITY))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> currentHashes() throws Exception {
        return ingestionService.loadKnowledgeDocuments().stream().collect(Collectors.toMap(
                Document::getId,
                d -> String.valueOf(d.getMetadata().get(KnowledgeIngestionService.METADATA_CONTENT_SHA))));
    }

    @Test
    void loadsEveryKnowledgeDocumentFromTheClasspath() throws Exception {
        assertThat(ingestionService.loadKnowledgeDocuments()).hasSize(7);
    }

    @Test
    void eachDocumentGetsAStableCategoryDerivedFromItsFilename() throws Exception {
        List<Document> documents = ingestionService.loadKnowledgeDocuments();

        assertThat(documents).extracting(doc -> doc.getMetadata().get("category"))
                .containsExactlyInAnyOrder(
                        "returns-policy", "refund-timing", "cancellation-policy", "shipping-and-delivery",
                        "payment-issues", "claims-damaged-wrong-missing", "human-escalation");
    }

    @Test
    void everyDocumentHasNonBlankContentAndExpectedMetadataKeys() throws Exception {
        List<Document> documents = ingestionService.loadKnowledgeDocuments();

        assertThat(documents).allSatisfy(doc -> {
            assertThat(doc.getText()).isNotBlank();
            assertThat(doc.getMetadata()).containsKeys("category", "source", "section", "policyVersion",
                    KnowledgeIngestionService.METADATA_MANAGED_BY, KnowledgeIngestionService.METADATA_CONTENT_SHA,
                    KnowledgeIngestionService.METADATA_EMBEDDING_IDENTITY);
        });
    }

    @Test
    void loadedDocumentsCarryManagedByMarkerAndContentHash() throws Exception {
        List<Document> documents = ingestionService.loadKnowledgeDocuments();

        assertThat(documents).allSatisfy(doc -> {
            assertThat(doc.getMetadata().get(KnowledgeIngestionService.METADATA_MANAGED_BY))
                    .isEqualTo(KnowledgeIngestionService.MANAGED_BY);
            String hash = String.valueOf(doc.getMetadata().get(KnowledgeIngestionService.METADATA_CONTENT_SHA));
            assertThat(hash).matches("[0-9a-f]{64}");
            // The hash tracks the actual content, not the id.
            assertThat(hash).isEqualTo(KnowledgeIngestionService.sha256Hex(
                    doc.getText().getBytes(StandardCharsets.UTF_8)));
        });
    }

    @Test
    void documentIdIsStableAcrossRepeatedCallsForTheSameCategory() {
        assertThat(KnowledgeIngestionService.stableId("returns-policy"))
                .isEqualTo(KnowledgeIngestionService.stableId("returns-policy"));
    }

    @Test
    void differentCategoriesProduceDifferentIds() {
        assertThat(KnowledgeIngestionService.stableId("returns-policy"))
                .isNotEqualTo(KnowledgeIngestionService.stableId("refund-timing"));
    }

    @Test
    void unchangedDocumentsAreNotReEmbedded() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);

        serviceWithStoredHashes(vectorStore, currentHashes()).run();

        verify(vectorStore, never()).add(anyList());
        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    void changedDocumentsAreReplacedInOneBatch() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        Map<String, String> storedHashes = currentHashes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> "0".repeat(64)));

        serviceWithStoredHashes(vectorStore, storedHashes).run();

        // Batching: one delete of all replacement ids, then one add of
        // all replacement documents - not one delete+add per document.
        List<Document> documents = ingestionService.loadKnowledgeDocuments();
        List<String> expectedIds = documents.stream().map(Document::getId).toList();
        verify(vectorStore, times(1)).delete(expectedIds);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> addCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(addCaptor.capture());
        assertThat(addCaptor.getValue()).hasSize(documents.size());
        assertThat(addCaptor.getValue().stream().map(Document::getId).toList())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    @Test
    void staleManagedRowsNoLongerPresentOnTheClasspathAreRemoved() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        Map<String, String> storedHashes = new java.util.HashMap<>(currentHashes());
        String staleId = UUID.randomUUID().toString(); // simulates a managed row from a since-deleted knowledge file
        storedHashes.put(staleId, "0".repeat(64));

        serviceWithStoredHashes(vectorStore, storedHashes).run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).anySatisfy(deleted -> assertThat(deleted).containsExactly(staleId));
        // Unchanged current documents were skipped, not re-added.
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void identityMismatchReEmbedsEvenWhenContentHashMatches() throws Exception {
        // The provider-switch trap: same content, same hash, but rows were
        // embedded with a different model. They must be re-embedded, never
        // queried with vectors from a different space.
        VectorStore vectorStore = mock(VectorStore.class);
        Map<String, String> storedIdentities = currentHashes().keySet().stream()
                .collect(Collectors.toMap(id -> id, id -> "ollama:bge-m3:1024"));
        KnowledgeIngestionService voyageService =
                new KnowledgeIngestionService(vectorStore, jdbcTemplate, VOYAGE_PROPERTIES) {
                    @Override
                    Map<String, String> loadStoredHashes() {
                        try {
                            return currentHashes();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    Map<String, String> loadStoredEmbeddingIdentities() {
                        return storedIdentities;
                    }
                };
        voyageService.run();

        // Every document was replaced (one batch delete + one batch
        // add), none skipped.
        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore, times(1)).delete(anyList());
    }

    @Test
    void failedSyncNeverFailsApplicationStartup() {
        VectorStore vectorStore = mock(VectorStore.class);
        org.mockito.Mockito.doThrow(new RuntimeException("Ollama is down"))
                .when(vectorStore).delete(anyList());
        KnowledgeIngestionService service = serviceWithStoredHashes(vectorStore, Map.of());

        // run() must swallow the embedding failure: the app boots and keeps
        // serving with the existing vector_store rows.
        assertThatCode(service::run).doesNotThrowAnyException();
    }

    /** A service with no stored rows (so all 7 documents are replacements) and a no-op backoff sleep, so retry tests run instantly. */
    private KnowledgeIngestionService serviceWithNoStoredRowsAndNoSleep(VectorStore store) {
        return new KnowledgeIngestionService(store, jdbcTemplate, LOCAL_PROPERTIES) {
            @Override
            Map<String, String> loadStoredHashes() {
                return Map.of();
            }

            @Override
            Map<String, String> loadStoredEmbeddingIdentities() {
                return Map.of();
            }

            @Override
            void sleepForBackoff(Duration delay) {
                // No sleep in tests: the retry count, not the wait, is under test.
            }
        };
    }

    @Test
    void batchAddRetriesOnRetryableFailureThenSucceeds() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new VoyageEmbeddingException("Voyage embedding request failed with HTTP 429", true))
                .doThrow(new VoyageEmbeddingException("Voyage embedding request failed with HTTP 429", true))
                .doNothing()
                .when(vectorStore).add(anyList());

        serviceWithNoStoredRowsAndNoSleep(vectorStore).run();

        // Startup-only backoff: two failed attempts, third succeeds.
        // The delete is repeated on every attempt so a retry never hits
        // a primary-key conflict from a partially inserted batch.
        verify(vectorStore, times(3)).add(anyList());
        verify(vectorStore, times(3)).delete(anyList());
    }

    @Test
    void batchAddGivesUpAfterFiveAttemptsButStartupStillSucceeds() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new VoyageEmbeddingException("Voyage embedding request failed with HTTP 429", true))
                .when(vectorStore).add(anyList());

        assertThatCode(serviceWithNoStoredRowsAndNoSleep(vectorStore)::run).doesNotThrowAnyException();

        // 1 initial attempt + 4 retries = 5 attempts in total.
        verify(vectorStore, times(5)).add(anyList());
        verify(vectorStore, times(5)).delete(anyList());
    }

    @Test
    void batchAddDoesNotRetryNonRetryableFailure() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new VoyageEmbeddingException("Voyage embedding request failed with HTTP 401", false))
                .when(vectorStore).add(anyList());

        assertThatCode(serviceWithNoStoredRowsAndNoSleep(vectorStore)::run).doesNotThrowAnyException();

        // 401 will not fix itself by waiting: exactly one attempt.
        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore, times(1)).delete(anyList());
    }
}
