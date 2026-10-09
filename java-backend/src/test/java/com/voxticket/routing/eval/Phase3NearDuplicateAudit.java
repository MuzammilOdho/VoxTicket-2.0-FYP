package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.voxticket.routing.RoutingExample;
import com.voxticket.routing.RoutingExampleLoader;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.embedding.OnnxMultilingualE5EmbeddingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3: semantic near-duplicate audit of the evaluation corpus against the
 * frozen 96 routing prototypes, using the real ONNX embedding model.
 *
 * <p>OPT-IN ONLY. Run BEFORE the main evaluation suite:
 * <pre>
 * -Dvoxticket.phase3.audit=true
 * -Dvoxticket.routing.model.path=/home/hatch/workspace/models/e5
 * </pre>
 *
 * <p>High cosine similarity is reported, never auto-deleted: obvious paraphrase
 * copies are replaced by hand before the final experiment runs.
 */
@EnabledIfSystemProperty(named = "voxticket.phase3.audit", matches = "true")
class Phase3NearDuplicateAudit {

    private static final double REPORT_THRESHOLD = 0.90;
    private static final double SUSPICIOUS_THRESHOLD = 0.95;

    @Test
    void auditAgainstPrototypes() throws Exception {
        String modelPath = System.getProperty("voxticket.routing.model.path");
        if (modelPath == null || modelPath.isBlank()) {
            fail("Near-duplicate audit needs -Dvoxticket.routing.model.path=/path/to/model/dir");
        }
        Path resultsDir = Paths.get("evaluation/routing/results");
        Files.createDirectories(resultsDir);

        List<EvalExample> all = new ArrayList<>(
                EvalCorpus.load(Paths.get("evaluation/routing/corpus/validation.json")));
        all.addAll(EvalCorpus.load(Paths.get("evaluation/routing/corpus/final-test.json")));

        RoutingExampleLoader loader =
                new RoutingExampleLoader(new DefaultResourceLoader(), new JsonMapper());
        List<RoutingExample> prototypes =
                loader.load("classpath:routing/examples-v1.json");
        assertThat(prototypes).hasSize(96);

        record Hit(String exampleId, String split, String language, String expectedTier,
                   double maxCosineSim, String nearestPrototypeId, String nearestPrototypeTier) {
        }
        List<Hit> hits = new ArrayList<>();
        try (OnnxMultilingualE5EmbeddingService embedding = new OnnxMultilingualE5EmbeddingService(
                Paths.get(modelPath))) {
            List<float[]> prototypeVectors = new ArrayList<>(prototypes.size());
            for (RoutingExample prototype : prototypes) {
                prototypeVectors.add(embedding.embed(prototype.text()));
            }
            for (EvalExample example : all) {
                float[] query = embedding.embed(example.text());
                double best = -2.0;
                int bestIndex = -1;
                for (int i = 0; i < prototypeVectors.size(); i++) {
                    double sim = dot(query, prototypeVectors.get(i));
                    if (sim > best) {
                        best = sim;
                        bestIndex = i;
                    }
                }
                RoutingExample nearest = prototypes.get(bestIndex);
                hits.add(new Hit(example.id(), example.split().name(), example.language().name(),
                        example.expectedTier().name(), best, nearest.id(), nearest.tier().name()));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::maxCosineSim).reversed());

        StringBuilder csv = new StringBuilder(
                "exampleId,split,language,expectedTier,maxCosineSim,nearestPrototypeId,nearestPrototypeTier\n");
        for (Hit hit : hits) {
            csv.append(String.format(Locale.US, "%s,%s,%s,%s,%.6f,%s,%s%n",
                    hit.exampleId(), hit.split(), hit.language(), hit.expectedTier(),
                    hit.maxCosineSim(), hit.nearestPrototypeId(), hit.nearestPrototypeTier()));
        }
        Files.writeString(resultsDir.resolve("near-duplicate-audit.csv"), csv.toString());

        long suspicious = hits.stream().filter(h -> h.maxCosineSim() >= SUSPICIOUS_THRESHOLD).count();
        long reported = hits.stream().filter(h -> h.maxCosineSim() >= REPORT_THRESHOLD).count();
        System.out.println("Phase3 near-duplicate audit: " + hits.size() + " examples, "
                + reported + " >= " + REPORT_THRESHOLD + ", "
                + suspicious + " >= " + SUSPICIOUS_THRESHOLD + " (suspicious)");
        for (Hit hit : hits) {
            if (hit.maxCosineSim() >= SUSPICIOUS_THRESHOLD) {
                System.out.println("  SUSPICIOUS " + hit.exampleId()
                        + " sim=" + String.format(Locale.US, "%.4f", hit.maxCosineSim())
                        + " nearest=" + hit.nearestPrototypeId());
            }
        }
        // Exact normalized overlap is a hard failure (also covered hermetically).
        assertThat(suspicious).as("examples nearly identical to a prototype (>= "
                + SUSPICIOUS_THRESHOLD + ") - replace before the final experiment").isZero();
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != RoutingEmbeddingService.DIMENSION
                || b.length != RoutingEmbeddingService.DIMENSION) {
            throw new IllegalArgumentException("Routing vectors must be "
                    + RoutingEmbeddingService.DIMENSION + "-dimensional");
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
