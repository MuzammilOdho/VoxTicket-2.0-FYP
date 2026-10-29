package com.voxticket.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.voxticket.embedding.EmbeddingProperties;
import com.voxticket.embedding.VoyageEmbeddingException;
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
 *   <li>Changed documents are replaced as ONE batch (one delete of all
 *       replacement ids, then one add of all replacement documents), not
 *       one document at a time. With the batched-document path in
 *       VoyageEmbeddingModel, a 7-document Voyage migration is one
 *       Voyage embedding call, not 7, plus the routing batch: 2 calls
 *       at startup instead of 8. That is the batching half of the
 *       Voyage rate-limit fix (docs.voyageai.com rate-limits page:
 *       larger batches reduce RPM for the same documents).</li>
 *   <li>Startup-only backoff: the batch add is retried on retryable
 *       failures only (Voyage 429/5xx, see
 *       {@link #isRetryableFailure(Throwable)}), with delays of 2s,
 *       8s, 20s, then 60s - 5 attempts in total, worst case about 90
 *       seconds of waiting plus call times. The 60s delay is deliberate:
 *       it covers a full per-minute rate-limit window. This backoff
 *       lives ONLY here, at startup. Per-turn embedding calls keep
 *       the fast client retry (one retry after 500ms) so a voice turn
 *       never stalls for a minute. That is the backoff half of the
 *       fix, following the same official guidance (exponential
 *       backoff on 429, up to a 60s wait).</li>
 *   <li>Honest limit of the batch: if every backoff attempt fails, the
 *       batch documents are missing until the next boot, because the
 *       delete already happened. That is the same failure mode the
 *       old per-document code had for one document, extended to the
 *       batch. The backoff makes a transient 429 recover inside the
 *       same boot instead; a persistent Voyage outage still leaves
 *       the batch missing, and the next boot retries from scratch.
 *       The app itself never fails to start because of this: see the
 *       next bullet.</li>
 *   <li>Embedding identity: a row is reused only when its content hash AND
 *       its {@code embedding_identity} metadata match the current
 *       configuration (provider + model + dimensions). Rows written by an
 *       older provider/model - including rows with no identity at all, from
 *       before this field existed - are re-embedded, never queried with
 *       vectors from a different space. That is what makes switching
 *       Ollama bge-m3 to Voyage voyage-4-lite (or voyage-4-lite to
 *       voyage-4) safe without a manual delete step.</li>
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
    static final String METADATA_EMBEDDING_IDENTITY = "embedding_identity";

    /**
     * Startup-only backoff delays between batch attempts, in order.
     * Attempts = delays + 1 = 5. Total waiting time across all retries
     * is 2 + 8 + 20 + 60 = 90 seconds, plus the embedding calls
     * themselves. Delays grow so a transient 5xx clears quickly while
     * a per-minute rate-limit window is covered by the final 60s wait.
     * Tests override {@link #sleepForBackoff(Duration)} so they never
     * actually wait.
     */
    private static final Duration[] INGESTION_BACKOFF_DELAYS = {
            Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(20), Duration.ofSeconds(60)
    };

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProperties embeddingProperties;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
            EmbeddingProperties embeddingProperties) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProperties = embeddingProperties;
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
        Map<String, String> storedIdentities = loadStoredEmbeddingIdentities();

        int skipped = 0;
        List<Document> replacements = new ArrayList<>();
        for (Document document : documents) {
            String storedHash = storedHashes.get(document.getId());
            String storedIdentity = storedIdentities.get(document.getId());
            String currentIdentity = String.valueOf(document.getMetadata().get(METADATA_EMBEDDING_IDENTITY));
            if (storedHash != null && storedHash.equals(document.getMetadata().get(METADATA_CONTENT_SHA))
                    && currentIdentity.equals(storedIdentity)) {
                skipped++;
                continue;
            }
            replacements.add(document);
        }

        int replaced = 0;
        if (!replacements.isEmpty()) {
            // Replace the whole batch at once: the stable ids already exist
            // (from a previous boot or the pre-Phase-8 ingestion), and a
            // plain add would violate the primary key, so the old rows
            // are deleted first, in one call, then the new rows are added
            // in one call. The delete is repeated on every retry attempt:
            // deleting an already-deleted id is a no-op, and deleting a
            // row a failed attempt managed to insert keeps the retry
            // from hitting the same primary-key conflict.
            addReplacementsWithBackoff(replacements);
            replaced = replacements.size();
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
     * Delete-then-add the replacement batch, retrying only retryable
     * failures with the startup-only backoff above. Package-visible so
     * tests can drive it through {@link #run(String...)} with a mocked
     * VectorStore; the sleep itself is overridable, see
     * {@link #sleepForBackoff(Duration)}.
     */
    void addReplacementsWithBackoff(List<Document> replacements) {
        List<String> ids = replacements.stream().map(Document::getId).toList();
        log.info("event=knowledge_ingestion_batch_start documents={}", replacements.size());
        for (int attempt = 0; attempt <= INGESTION_BACKOFF_DELAYS.length; attempt++) {
            try {
                vectorStore.delete(ids);
                vectorStore.add(replacements);
                log.info("event=knowledge_ingestion_batch_succeeded documents={} attempts={}",
                        replacements.size(), attempt + 1);
                return;
            } catch (Exception e) {
                boolean retryable = isRetryableFailure(e);
                if (!retryable || attempt == INGESTION_BACKOFF_DELAYS.length) {
                    if (e instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new RuntimeException(e);
                }
                Duration delay = INGESTION_BACKOFF_DELAYS[attempt];
                log.warn("event=knowledge_ingestion_batch_retry attempt={} nextDelaySeconds={} errorType={} errorMessage={} - retrying the batch, no voice turn is affected (startup only)",
                        attempt + 1, delay.toSeconds(), e.getClass().getSimpleName(), e.getMessage());
                try {
                    sleepForBackoff(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Knowledge ingestion backoff interrupted", interrupted);
                }
            }
        }
    }

    /**
     * Retryable means a Voyage 429/5xx somewhere in the cause chain.
     * The primary signal is {@link VoyageEmbeddingException#isRetryable()};
     * the message fallback covers a store that wraps the Voyage failure
     * in its own exception without preserving the type. Anything else
     * (400, 401, 403, bad response, database error) is not retried:
     * waiting will not fix it, and retrying it would only stall startup.
     * Package-visible for tests.
     */
    static boolean isRetryableFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof VoyageEmbeddingException voyage) {
                return voyage.isRetryable();
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("429") || message.contains("HTTP 5"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The only sleep in the ingestion path, isolated here so tests can
     * override it to a no-op. Production sleeps the full backoff delay.
     * Package-visible for tests.
     */
    void sleepForBackoff(Duration delay) throws InterruptedException {
        Thread.sleep(delay);
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

    /**
     * The embedding identities of rows this service manages, keyed by
     * document id. Rows written before this field existed return null
     * here, which sync treats as a mismatch (re-embed), never as a match.
     * Package-visible for tests, mirroring {@link #loadStoredHashes()}.
     */
    Map<String, String> loadStoredEmbeddingIdentities() {
        Map<String, String> identities = new HashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler =
                rs -> identities.put(rs.getString("id"), rs.getString("embedding_identity"));
        jdbcTemplate.query(
                "SELECT id, metadata->>'" + METADATA_EMBEDDING_IDENTITY + "' AS embedding_identity "
                        + "FROM vector_store WHERE metadata->>'" + METADATA_MANAGED_BY + "' = '" + MANAGED_BY + "'",
                handler);
        return identities;
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
                            METADATA_CONTENT_SHA, sha256Hex(bytes),
                            METADATA_EMBEDDING_IDENTITY, embeddingProperties.embeddingIdentity()))
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
