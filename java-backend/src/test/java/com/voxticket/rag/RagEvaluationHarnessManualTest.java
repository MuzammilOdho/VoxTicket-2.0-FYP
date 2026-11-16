package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in E4 harness: exercises the REAL {@link RagService} against the same
 * embedding provider and PostgreSQL/pgvector store used by the running
 * application. Gated ONLY on {@code -Dvoxticket.eval.rag=true}, so it can
 * never break the default gate and never runs by accident.
 *
 * <p>Deliberately NO {@code @Tag("manual")}: the module pom hard-codes
 * {@code <excludedGroups>manual</excludedGroups>} as a literal, and Maven
 * command-line flags cannot override a literal pom value — the v4 runner
 * passed {@code -Dgroups=manual -DexcludedGroups=} and Surefire still
 * reported "Tests run: 0". The property gate alone is airtight here:
 * without the property, JUnit disables the test before any Spring
 * context or database is touched, so a plain {@code mvn test} reports it
 * as skipped and stays green.
 *
 * <p>Run it with:
 *
 * <pre>
 * mvn test -Dtest=RagEvaluationHarnessManualTest "-Dvoxticket.eval.rag=true" "-Dvoxticket.eval.rag.dataset=&lt;dataset&gt;" "-Dvoxticket.eval.rag.output=&lt;output&gt;"
 * </pre>
 *
 * <p>Dataset contract: a JSON object with a {@code questions} array. Each
 * question needs {@code id} and {@code question}; {@code type} and
 * {@code category} are read when present ({@code category} must be the
 * policy-document stem, or absent for unsupported questions). The
 * evaluation package's {@code datasets/rag_questions_v2.json} satisfies
 * this contract.
 *
 * <p>This harness only measures retrieval. Answer quality is collected
 * separately and scored by a human; the harness never substitutes an LLM
 * judge for that judgement. The output header records the embedding
 * provider environment variable when set
 * ({@code VOXTICKET_EMBEDDING_PROVIDER}); record the actual provider and
 * model in the run header before quoting E4 numbers, because the yaml
 * default is Ollama bge-m3 and Voyage applies only when that variable is
 * set. The hard-coded {@code ragTopK} (3) and
 * {@code ragSimilarityThreshold} (0.35) must match {@code application.yaml}.
 *
 * <p>Uncompiled in the sandbox: the first {@code mvn test} compile after
 * copying this file verifies it. If test compilation fails, delete the
 * file and re-run; the default gate does not depend on it.
 */
@EnabledIfSystemProperty(named = "voxticket.eval.rag", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RagEvaluationHarnessManualTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RagService ragService;

    @Test
    void retrieveEveryDatasetQuestionAndWriteRawResults() throws Exception {
        Path dataset = Path.of(System.getProperty(
                "voxticket.eval.rag.dataset", "../evaluation/datasets/rag/questions.json"));
        Path output = Path.of(System.getProperty(
                "voxticket.eval.rag.output", "../evaluation/evidence/e4/retrieval-raw.json"));

        byte[] datasetBytes = Files.readAllBytes(dataset);
        JsonNode root = MAPPER.readTree(datasetBytes);
        JsonNode questions = root.get("questions");
        assertThat(questions).isNotNull();
        assertThat(questions.size()).isGreaterThanOrEqualTo(1);

        List<ObjectNode> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode question : questions) {
            if (!question.hasNonNull("id") || !question.hasNonNull("question")) {
                throw new IllegalArgumentException(
                        "Dataset question at index " + index + " lacks 'id' or 'question'");
            }
            String id = question.get("id").asText();
            String text = question.get("question").asText();
            String type = question.hasNonNull("type") ? question.get("type").asText() : "unknown";
            String category = question.hasNonNull("category") ? question.get("category").asText() : null;

            long started = System.nanoTime();
            RagService.RagSearchResult result = ragService.searchPolicyDetailed(text);
            long wallMs = (System.nanoTime() - started) / 1_000_000;

            ObjectNode row = MAPPER.createObjectNode();
            row.put("id", id);
            row.put("type", type);
            row.put("expectedCategory", category);
            row.put("question", text);
            row.put("cacheHit", result.cacheHit());
            row.put("serviceDurationMs", result.durationMs());
            row.put("wallMs", wallMs);
            ArrayNode hits = row.putArray("hits");
            for (RagService.DocHit hit : result.hits()) {
                ObjectNode hitNode = hits.addObject();
                hitNode.put("docId", hit.docId());
                hitNode.put("category", hit.category());
                hitNode.put("similarity", hit.similarity());
            }
            rows.add(row);
            System.out.printf(
                    "E4_RETRIEVAL id=%s type=%s expected=%s hits=%d top=%s wallMs=%d%n",
                    id,
                    type,
                    category == null ? "unsupported" : category,
                    result.hits().size(),
                    result.hits().isEmpty() ? "none" : result.hits().get(0).category(),
                    wallMs);
            index++;
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("datasetPath", dataset.toAbsolutePath().normalize().toString());
        out.put("datasetSha256", sha256(datasetBytes));
        out.put("questionCount", rows.size());
        out.put("ragTopK", 3);
        out.put("ragSimilarityThreshold", 0.35);
        String providerEnv = System.getenv("VOXTICKET_EMBEDDING_PROVIDER");
        out.put("embeddingProviderEnv", providerEnv == null ? "not set (default applies)" : providerEnv);
        out.put("note", "Retrieval-only run through the real RagService. Answer quality is collected separately and human-scored.");
        ArrayNode rowArray = out.putArray("rows");
        rows.forEach(rowArray::add);

        Files.createDirectories(output.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), out);
        System.out.println("E4_RETRIEVAL wrote " + output.toAbsolutePath().normalize());

        assertThat(rows).hasSize(questions.size());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
