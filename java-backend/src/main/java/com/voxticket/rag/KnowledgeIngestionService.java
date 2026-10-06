package com.voxticket.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * <p>Phase 8 sync semantics (idempotent, change-detected, startup-safe):
 *
 * <ul>
 *   <li>Every ingestion-managed row carries {@code managed_by=knowledge-ingestion}
 *       metadata. The stale-row delete is scoped to those rows only, so rows
 *       added by any other means survive restarts.</li>
 *   <li>Each document's SHA-256 content hash is stored as {@code content_sha256}
 *       metadata. Documents whose hash matches the stored row are skipped -
 *       nothing is re-embedded when the files are unchanged.</li>
 *   <li>Changed documents are replaced one at a time (delete then add the
 *       single row); the whole-sync delete-everything-first pattern is gone,
 *       so a failed embedding call cannot wipe the knowledge base.</li>
 *   <li>A failed sync never fails application startup. The rows from a
 *       previous successful boot remain in {@code vector_store} and the app
 *       serves with them; the failure is logged loudly for operators.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class KnowledgeIngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final String KNOWLEDGE_LOCATION_PATTERN = "classpath:knowledge/*.md";
    static final String POLICY_VERSION = "2026.09";
    static final String MANAGED_BY = "knowledge-ingestion";
    static final String METADATA_MANAGED_BY = "managed_by";
    static final String METADATA_CONTENT_SHA = "content_sha256";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        log.info("event=knowledge_ingestion_start locationPattern={}", KNOWLEDGE_LOCATION_PATTERN);
        try {
            syncKnowledgeBase();
        } catch (Exception e) {
            // Startup-safe: never abort the application because the embedding
            // backend was unavailable. Existing vector_store rows (from a
            // previous successful boot) keep serving; the next restart retries.
            log.error("event=knowledge_ingestion_failed errorType={} errorMessage={} - continuing startup with existing vector_store rows",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    void syncKnowledgeBase() throws IOException {
        List<Document> documents = loadKnowledgeDocuments();
        Set<String> currentIds = documents.stream().map(Document::getId).collect(Collectors.toSet());
        Map<String, String> storedHashes = loadStoredHashes();

        int replaced = 0;
        int skipped = 0;
        for (Document document : documents) {
            String storedHash = storedHashes.get(document.getId());
            if (storedHash != null && storedHash.equals(document.getMetadata().get(METADATA_CONTENT_SHA))) {
                skipped++;
                continue;
            }
            // Replace exactly this row: the stable id already exists (from a
            // previous boot or the pre-Phase-8 ingestion), and a plain add
            // would violate the primary key.
            vectorStore.delete(List.of(document.getId()));
            vectorStore.add(List.of(document));
            replaced++;
        }

        List<String> staleIds = storedHashes.keySet().stream()
                .filter(id -> !currentIds.contains(id))
                .toList();
        if (!staleIds.isEmpty()) {
            vectorStore.delete(staleIds);
        }

        if (documents.isEmpty()) {
            log.warn("event=knowledge_ingestion filesDiscovered=0 documentsReplaced={} documentsSkipped={} documentsRemoved={} policyVersion={}",
                    replaced, skipped, staleIds.size(), POLICY_VERSION);
        } else {
            log.info("event=knowledge_ingestion filesDiscovered={} documentsReplaced={} documentsSkipped={} documentsRemoved={} policyVersion={}",
                    documents.size(), replaced, skipped, staleIds.size(), POLICY_VERSION);
        }
    }

    /**
     * The content hashes of rows this service manages, keyed by document id.
     * Package-visible for tests. Reads metadata in SQL so the service never
     * has to parse the JSON column in Java.
     */
    Map<String, String> loadStoredHashes() {
        Map<String, String> hashes = new HashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler =
                rs -> hashes.put(rs.getString("id"), rs.getString("content_sha"));
        jdbcTemplate.query(
                "SELECT id, metadata->>'" + METADATA_CONTENT_SHA + "' AS content_sha "
                        + "FROM vector_store WHERE metadata->>'" + METADATA_MANAGED_BY + "' = '" + MANAGED_BY + "'",
                handler);
        return hashes;
    }

    List<Document> loadKnowledgeDocuments() throws IOException {
        Resource[] resources = resourceResolver.getResources(KNOWLEDGE_LOCATION_PATTERN);
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String category = categoryFor(resource);
            byte[] bytes = readContentBytes(resource);
            String content = new String(bytes, StandardCharsets.UTF_8);
            documents.add(Document.builder()
                    .id(stableId(category))
                    .text(content)
                    .metadata(Map.of(
                            "category", category,
                            "source", String.valueOf(resource.getFilename()),
                            "section", "full-document",
                            "policyVersion", POLICY_VERSION,
                            METADATA_MANAGED_BY, MANAGED_BY,
                            METADATA_CONTENT_SHA, sha256Hex(bytes)))
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

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] readContentBytes(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
