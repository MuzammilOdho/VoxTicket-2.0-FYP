package com.voxticket.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Spec §43. Unlike {@link com.voxticket.persistence.seed.DataSeeder}, this is
 * NOT dev/test-only - the knowledge base is real reference content a real
 * deployment needs, not synthetic test data. It's excluded specifically from
 * the "test" profile so the automated test suite never depends on a live
 * Ollama instance being reachable; Phase 6's own tests exercise the loading
 * and retrieval logic directly instead of through this runner.
 *
 * <p>Idempotency (spec §43: "Knowledge ingestion should be idempotent") is
 * achieved the same way as DataSeeder: skip entirely if the vector store
 * already has content, rather than relying on upsert-by-id semantics.
 */
@Component
@Profile("!test")
public class KnowledgeIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final String KNOWLEDGE_LOCATION_PATTERN = "classpath:knowledge/*.md";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        Integer existingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class);
        if (existingCount != null && existingCount > 0) {
            log.info("Vector store already has {} entries - skipping knowledge ingestion (idempotent).", existingCount);
            return;
        }

        List<Document> documents = loadKnowledgeDocuments();
        if (documents.isEmpty()) {
            log.warn("No knowledge documents found under {} - RAG will have nothing to retrieve.", KNOWLEDGE_LOCATION_PATTERN);
            return;
        }
        vectorStore.add(documents);
        log.info("Ingested {} knowledge documents into the vector store.", documents.size());
    }

    List<Document> loadKnowledgeDocuments() throws IOException {
        Resource[] resources = resourceResolver.getResources(KNOWLEDGE_LOCATION_PATTERN);
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String category = categoryFor(resource);
            String content = readContent(resource);
            documents.add(new Document(content, Map.of("category", category, "source", String.valueOf(resource.getFilename()))));
        }
        return documents;
    }

    private String categoryFor(Resource resource) {
        String filename = resource.getFilename();
        return filename == null ? "policy" : filename.replaceFirst("\\.md$", "");
    }

    private String readContent(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}