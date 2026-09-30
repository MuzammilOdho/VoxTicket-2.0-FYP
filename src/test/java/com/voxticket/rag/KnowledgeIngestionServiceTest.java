package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final KnowledgeIngestionService ingestionService = new KnowledgeIngestionService(mock(VectorStore.class), jdbcTemplate);

    /** A service whose stored-hash lookup is controlled by the test. */
    private KnowledgeIngestionService serviceWithStoredHashes(VectorStore store, Map<String, String> storedHashes) {
        return new KnowledgeIngestionService(store, jdbcTemplate) {
            @Override
            Map<String, String> loadStoredHashes() {
                return storedHashes;
            }
        };
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
                    KnowledgeIngestionService.METADATA_MANAGED_BY, KnowledgeIngestionService.METADATA_CONTENT_SHA);
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
    void changedDocumentsAreReplacedOneAtATime() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        Map<String, String> storedHashes = currentHashes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> "0".repeat(64)));

        serviceWithStoredHashes(vectorStore, storedHashes).run();

        List<Document> documents = ingestionService.loadKnowledgeDocuments();
        for (Document document : documents) {
            verify(vectorStore).delete(List.of(document.getId()));
        }
        verify(vectorStore, org.mockito.Mockito.times(documents.size())).add(anyList());
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
    void failedSyncNeverFailsApplicationStartup() {
        VectorStore vectorStore = mock(VectorStore.class);
        org.mockito.Mockito.doThrow(new RuntimeException("Ollama is down"))
                .when(vectorStore).delete(anyList());
        KnowledgeIngestionService service = serviceWithStoredHashes(vectorStore, Map.of());

        // run() must swallow the embedding failure: the app boots and keeps
        // serving with the existing vector_store rows.
        assertThatCode(service::run).doesNotThrowAnyException();
    }
}
