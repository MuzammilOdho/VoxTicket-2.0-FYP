package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeIngestionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final KnowledgeIngestionService ingestionService = new KnowledgeIngestionService(mock(VectorStore.class), jdbcTemplate);

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
            assertThat(doc.getMetadata()).containsKeys("category", "source", "section", "policyVersion");
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
    void staleDocumentsNoLongerPresentOnTheClasspathAreRemoved() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(vectorStore, jdbcTemplate);

        List<String> currentIds = service.loadKnowledgeDocuments().stream().map(Document::getId).toList();
        String staleId = "11111111-1111-1111-1111-111111111111"; // simulates a row from a since-deleted knowledge file
        List<String> existingIdsInDb = new ArrayList<>(currentIds);
        existingIdsInDb.add(staleId);
        when(jdbcTemplate.queryForList(eq("SELECT id FROM vector_store"), eq(String.class))).thenReturn(existingIdsInDb);

        service.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).anySatisfy(deleted -> assertThat(deleted).containsExactly(staleId));
    }
}