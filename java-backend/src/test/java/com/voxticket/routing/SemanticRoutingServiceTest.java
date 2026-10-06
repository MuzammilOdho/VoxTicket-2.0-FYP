package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: the semantic service embeds the query exactly once per call and scores it
 * deterministically against the cached prototypes. The embedding model itself is stubbed -
 * ONNX inference is covered by the opt-in {@code OnnxEmbeddingServiceTest}.
 */
class SemanticRoutingServiceTest {

    private static float[] unit(double... values) {
        float[] out = new float[RoutingEmbeddingService.DIMENSION];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
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

    private static SemanticRoutingProperties properties() {
        return new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "",
                "classpath:routing/examples-v1.json", 3, 0.02, 0.02);
    }

    @Test
    void embedsExactlyOncePerScoreCall() {
        AtomicInteger calls = new AtomicInteger();
        RoutingEmbeddingService embedding = text -> {
            calls.incrementAndGet();
            return unit(1, 0, 0);
        };
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(unit(1, 0, 0)),
                ModelTier.TIER_2, List.of(unit(0, 1, 0))));
        var service = new SemanticRoutingService(embedding, index, properties());

        SemanticScores scores = service.score("Where is my order?");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(scores.simpleScore()).isEqualTo(1.0);
        assertThat(scores.complexScore()).isEqualTo(0.0);
        assertThat(scores.margin()).isEqualTo(-1.0);
    }

    @Test
    void marginIsComplexMinusSimple() {
        RoutingEmbeddingService embedding = text -> unit(1, 1, 0);
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(unit(1, 0, 0)),
                ModelTier.TIER_2, List.of(unit(0, 1, 0))));
        var service = new SemanticRoutingService(embedding, index, properties());

        SemanticScores scores = service.score("anything");

        double expected = 1 / Math.sqrt(2);
        assertThat(scores.simpleScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(scores.complexScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(scores.margin()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void nullMessageIsEmbeddedAsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        RoutingEmbeddingService embedding = text -> {
            calls.incrementAndGet();
            assertThat(text).isEqualTo("");
            return unit(1, 0, 0);
        };
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(unit(1, 0, 0)),
                ModelTier.TIER_2, List.of(unit(0, 1, 0))));

        new SemanticRoutingService(embedding, index, properties()).score(null);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void inferenceFailurePropagatesWithoutFallback() {
        RoutingEmbeddingService failing = text -> {
            throw new RoutingInferenceException("boom");
        };
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(unit(1, 0, 0)),
                ModelTier.TIER_2, List.of(unit(0, 1, 0))));

        assertThatThrownBy(() -> new SemanticRoutingService(failing, index, properties()).score("hi"))
                .isInstanceOf(RoutingInferenceException.class)
                .hasMessageContaining("boom");
    }
}
