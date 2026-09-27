package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: deterministic top-K-per-class cosine scoring over cached prototype vectors.
 * Uses tiny synthetic vectors (not the model) so the math is exactly checkable.
 */
class RoutingExampleIndexTest {

    private static float[] v(double... values) {
        float[] out = new float[RoutingEmbeddingService.DIMENSION];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        // normalize so cosine == dot product, mirroring the production contract
        double norm = 0;
        for (float x : out) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < out.length; i++) {
            out[i] /= norm;
        }
        return out;
    }

    private static RoutingExampleIndex index(float[] tier1, float[] tier2) {
        return RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(tier1),
                ModelTier.TIER_2, List.of(tier2)));
    }

    @Test
    void cosineSimilarityIsADotProductOnNormalizedVectors() {
        // query == tier1 prototype exactly -> similarity 1.0
        float[] proto = v(1, 0, 0);
        var index = index(proto, v(0, 1, 0));

        assertThat(index.topKMeanSimilarity(proto, ModelTier.TIER_1, 3)).isEqualTo(1.0);
        assertThat(index.topKMeanSimilarity(proto, ModelTier.TIER_2, 3)).isEqualTo(0.0);
    }

    @Test
    void topKMeanAveragesTheKHighestSimilarities() {
        float[] q = v(1, 0, 0);
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(v(1, 0, 0), v(1, 1, 0), v(0, 1, 0)),
                ModelTier.TIER_2, List.of(v(0, 0, 1))));

        // cosines vs TIER_1: 1.0, 1/sqrt(2)≈0.7071, 0.0 -> top-2 mean ≈ 0.8536
        double mean = index.topKMeanSimilarity(q, ModelTier.TIER_1, 2);

        assertThat(mean).isCloseTo((1.0 + 1 / Math.sqrt(2)) / 2, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void topKClampsToAvailablePrototypeCount() {
        float[] q = v(1, 0, 0);
        var index = index(v(1, 0, 0), v(0, 1, 0));

        assertThat(index.topKMeanSimilarity(q, ModelTier.TIER_1, 100)).isEqualTo(1.0);
        assertThat(index.topKMeanSimilarity(q, ModelTier.TIER_1, 0)).isEqualTo(1.0);
    }

    @Test
    void scoringIsDeterministic() {
        float[] q = v(0.3, 0.7, 0.1);
        var index = index(v(1, 0, 0), v(0, 1, 0));

        double first = index.topKMeanSimilarity(q, ModelTier.TIER_1, 3);
        double second = index.topKMeanSimilarity(q, ModelTier.TIER_1, 3);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void buildValidatesDimensionsAndNormalization() {
        RoutingEmbeddingService badDim = text -> new float[10];
        var examples = List.of(new RoutingExample("x", ModelTier.TIER_1, RoutingExampleLanguage.ENGLISH, Set.of(), "hi"));

        assertThatThrownBy(() -> RoutingExampleIndex.build(examples, badDim))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void buildRejectsUnnormalizedVectors() {
        RoutingEmbeddingService unnormalized = text -> {
            float[] vec = new float[RoutingEmbeddingService.DIMENSION];
            java.util.Arrays.fill(vec, 1.0f); // norm = sqrt(384), not 1
            return vec;
        };
        var examples = List.of(new RoutingExample("x", ModelTier.TIER_1, RoutingExampleLanguage.ENGLISH, Set.of(), "hi"));

        assertThatThrownBy(() -> RoutingExampleIndex.build(examples, unnormalized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L2-normalized");
    }

    @Test
    void buildRejectsTierWithNoPrototypes() {
        RoutingEmbeddingService svc = text -> v(1, 0, 0);
        var examples = List.of(new RoutingExample("x", ModelTier.TIER_1, RoutingExampleLanguage.ENGLISH, Set.of(), "hi"));

        assertThatThrownBy(() -> RoutingExampleIndex.build(examples, svc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIER_2");
    }

    @Test
    void prototypeCountsAreVisible() {
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(v(1, 0, 0), v(0, 1, 0)),
                ModelTier.TIER_2, List.of(v(0, 0, 1))));

        assertThat(index.prototypeCount(ModelTier.TIER_1)).isEqualTo(2);
        assertThat(index.prototypeCount(ModelTier.TIER_2)).isEqualTo(1);
    }
}
