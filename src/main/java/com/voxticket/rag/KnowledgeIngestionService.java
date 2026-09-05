package com.voxticket.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spec §43. Excluded from the "test" profile only - the knowledge base is
 * real reference content, not synthetic test data.
 *
 * <p>Full sync on every run, not just upsert: any row in vector_store whose
 * id isn't among the current classpath documents' ids is treated as
 * belonging to a deleted knowledge file and removed. Combined with the
 * stable, content-independent id (per category/filename), this means
 * editing a document's text replaces its row, and deleting a document's
 * file removes its row - neither accumulates duplicates nor leaves an
 * orphan.
 */
@Component
@Profile("!test")
public class KnowledgeIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final String KNOWLEDGE_LOCATION_PATTERN = "classpath:knowledge/*.md";
    static final String POLICY_VERSION = "2026.09";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("event=knowledge_ingestion_start locationPattern={}", KNOWLEDGE_LOCATION_PATTERN);

        List<Document> documents = loadKnowledgeDocuments();
        List<String> currentIds = documents.stream().map(Document::getId).toList();

        List<String> existingIds = jdbcTemplate.queryForList("SELECT id FROM vector_store", String.class);
        List<String> staleIds = existingIds.stream().filter(id -> !currentIds.contains(id)).toList();
        if (!staleIds.isEmpty()) {
            vectorStore.delete(staleIds);
        }

        if (documents.isEmpty()) {
            log.warn("event=knowledge_ingestion filesDiscovered=0 documentsUpserted=0 documentsRemoved={} policyVersion={}",
                    staleIds.size(), POLICY_VERSION);
            return;
        }

        vectorStore.delete(currentIds); // clear any prior version of exactly these documents before re-adding
        vectorStore.add(documents);
        log.info("event=knowledge_ingestion filesDiscovered={} documentsUpserted={} documentsRemoved={} policyVersion={}",
                documents.size(), documents.size(), staleIds.size(), POLICY_VERSION);
    }

    List<Document> loadKnowledgeDocuments() throws IOException {
        Resource[] resources = resourceResolver.getResources(KNOWLEDGE_LOCATION_PATTERN);
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String category = categoryFor(resource);
            String content = readContent(resource);
            documents.add(Document.builder()
                    .id(stableId(category))
                    .text(content)
                    .metadata(Map.of(
                            "category", category,
                            "source", String.valueOf(resource.getFilename()),
                            "section", "full-document",
                            "policyVersion", POLICY_VERSION))
                    .build());
        }
        return documents;
    }

    private String categoryFor(Resource resource) {
        String filename = resource.getFilename();
        return filename == null ? "policy" : filename.replaceFirst("\\.md$", "");
    }

    /** Deterministic per category, NOT per content - so an edited document's row is replaced, not duplicated or orphaned. */
    static String stableId(String category) {
        return UUID.nameUUIDFromBytes(("voxticket-knowledge:" + category).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String readContent(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}