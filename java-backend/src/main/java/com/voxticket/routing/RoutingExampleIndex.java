package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: the prototype set for semantic tier routing.
 *
 * <p>Each example is embedded ONCE at startup (via {@link RoutingEmbeddingService}, which
 * applies the e5 {@code "query: "} prefix identically to prototypes and live queries) and the
 * normalized vector is cached here. Per-turn work is pure cosine arithmetic against this
 * cache: no routing DB, no pgvector, no network, no ANN library.
 *
 * <p>Scoring is deterministic top-K per class: for each tier, take the K highest cosine
 * similarities between the query and that tier's prototype vectors and average them. Cosine
 * on L2-normalized vectors is a plain dot product.
 */
public class RoutingExampleIndex {

    /** Per-tier prototype vectors (already L2-normalized), in example order. */
    private final Map<ModelTier, List<float[]>> prototypesByTier;

    private RoutingExampleIndex(Map<ModelTier, List<float[]>> prototypesByTier) {
        Map<ModelTier, List<float[]>> copy = new EnumMap<>(ModelTier.class);
        prototypesByTier.forEach((tier, vectors) -> copy.put(tier, List.copyOf(vectors)));
        this.prototypesByTier = copy;
    }

    /**
     * Embeds every example once and caches the vectors. This is the ONLY per-example
     * embedding call in the application's lifetime.
     */
    public static RoutingExampleIndex build(List<RoutingExample> examples, RoutingEmbeddingService embeddingService) {
        Map<ModelTier, List<float[]>> byTier = new EnumMap<>(ModelTier.class);
        for (ModelTier tier : ModelTier.values()) {
            byTier.put(tier, new ArrayList<>());
        }
        for (RoutingExample example : examples) {
            float[] vector = embeddingService.embed(example.text());
            requireNormalized(vector, example.id());
            byTier.get(example.tier()).add(vector);
        }
        for (ModelTier tier : ModelTier.values()) {
            if (byTier.get(tier).isEmpty()) {
                throw new IllegalArgumentException("No routing prototypes for tier " + tier
                        + " - semantic routing cannot score that class");
            }
        }
        return new RoutingExampleIndex(byTier);
    }

    /** Mean of the top-K cosine similarities of the query against one tier's prototypes. */
    public double topKMeanSimilarity(float[] query, ModelTier tier, int topK) {
        List<float[]> prototypes = prototypesByTier.get(tier);
        if (prototypes == null || prototypes.isEmpty()) {
            throw new IllegalArgumentException("No routing prototypes for tier " + tier);
        }
        int k = Math.min(Math.max(topK, 1), prototypes.size());
        double[] scores = new double[prototypes.size()];
        for (int i = 0; i < prototypes.size(); i++) {
            scores[i] = dot(query, prototypes.get(i));
        }
        java.util.Arrays.sort(scores);
        double sum = 0.0;
        for (int i = scores.length - k; i < scores.length; i++) {
            sum += scores[i];
        }
        return sum / k;
    }

    /** Number of cached prototype vectors for a tier (observability/testing). */
    public int prototypeCount(ModelTier tier) {
        List<float[]> vectors = prototypesByTier.get(tier);
        return vectors == null ? 0 : vectors.size();
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != RoutingEmbeddingService.DIMENSION || b.length != RoutingEmbeddingService.DIMENSION) {
            throw new IllegalArgumentException("Routing vectors must be "
                    + RoutingEmbeddingService.DIMENSION + "-dimensional");
        }
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static void requireNormalized(float[] vector, String exampleId) {
        if (vector.length != RoutingEmbeddingService.DIMENSION) {
            throw new IllegalArgumentException("Prototype embedding for example '" + exampleId
                    + "' has dimension " + vector.length + ", expected " + RoutingEmbeddingService.DIMENSION);
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (Math.abs(norm - 1.0) > 1e-3) {
            throw new IllegalArgumentException("Prototype embedding for example '" + exampleId
                    + "' is not L2-normalized (norm=" + norm + ")");
        }
    }

    /**
     * Test seam: build directly from precomputed vectors (no embedding service). Public so
     * tests outside this package (e.g. {@code ModelSelectorTest}) can stub the semantic leg.
     */
    public static RoutingExampleIndex fromVectors(Map<ModelTier, List<float[]>> prototypesByTier) {
        return new RoutingExampleIndex(prototypesByTier);
    }
}
