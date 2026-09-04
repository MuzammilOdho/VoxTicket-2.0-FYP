package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeIngestionServiceTest {

    private final KnowledgeIngestionService ingestionService =
            new KnowledgeIngestionService(mock(VectorStore.class), mock(JdbcTemplate.class));

    @Test
    void loadsEveryKnowledgeDocumentFromTheClasspath() throws Exception {
        List<Document> documents = ingestionService.loadKnowledgeDocuments();

        assertThat(documents).hasSize(7);
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
    void everyDocumentHasNonBlankContent() throws Exception {
        List<Document> documents = ingestionService.loadKnowledgeDocuments();

        assertThat(documents).allSatisfy(doc -> assertThat(doc.getText()).isNotBlank());
    }
}