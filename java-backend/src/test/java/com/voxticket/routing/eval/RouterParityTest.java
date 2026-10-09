package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.agent.ModelTier;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingInferenceException;
import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingReason;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import com.voxticket.routing.StructuralRoutingProperties;
import com.voxticket.routing.StructuralRoutingSignal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: proves the evaluation harness reuses the production routing
 * composition instead of reimplementing it. Hermetic - deterministic stub
 * embeddings, no ONNX model.
 */
class RouterParityTest {

    /** Deterministic stub: a stable L2-normalized 384-d vector derived from the text hash. */
    static final class StubEmbeddingService implements RoutingEmbeddingService {
        @Override
        public float[] embed(String text) {
            long seed = text == null ? 0 : text.hashCode();
            float[] vector = new float[DIMENSION];
            double norm = 0.0;
            for (int i = 0; i < DIMENSION; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                double value = ((seed >>> 11) % 1000) / 1000.0 + 0.001;
                vector[i] = (float) value;
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= norm;
            }
            return vector;
        }
    }

    static RoutingProperties hybridProperties() {
        return new RoutingProperties(
                RoutingStrategy.HYBRID,
                new StructuralRoutingProperties(300, 2),
                new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "",
                        "classpath:routing/examples-v1.json", 3, 0.02, 0.02));
    }

    static EvalExample example(String id, ModelTier tier, String text) {
        return new EvalExample(id, EvalSplit.VALIDATION, EvalLanguage.ENGLISH,
                tier, text, List.of("test"), "parity fixture");
    }

    @Test
    void harnessMatchesModelSelectorOnAllStrategies() {
        List<EvalExample> examples = List.of(
                example("VAL-EN-T1-001", ModelTier.TIER_1, "Where is my order ORD-48210?"),
                example("VAL-EN-T2-001", ModelTier.TIER_2,
                        "Cancel ORD-48210 and change the address for ORD-48211."),
                example("VAL-EN-T2-002", ModelTier.TIER_2,
                        "I ordered blue but got red; if blue is in stock replace it, otherwise refund me."),
                example("VAL-EN-T1-002", ModelTier.TIER_1, "Hi"));
        try (Phase3Router router = Phase3Router.create(hybridProperties(), new StubEmbeddingService())) {
            List<String> mismatches = router.parityCheck(examples);
            assertThat(mismatches).as("harness vs ModelSelector mismatches: %s", mismatches).isEmpty();
        }
    }

    @Test
    void structuralShortCircuitSpendsNoEmbedding() {
        List<EvalExample> examples = List.of(
                example("VAL-EN-T1-001", ModelTier.TIER_1, "Where is my order ORD-48210?"),
                example("VAL-EN-T2-001", ModelTier.TIER_2,
                        "Cancel ORD-48210 and change the address for ORD-48211."));
        try (Phase3Router router = Phase3Router.create(hybridProperties(), new StubEmbeddingService())) {
            List<ScoredExample> scored = router.scoreForHybrid(examples);
            // 96 prototype embeddings + 1 query embedding (the multi-order turn short-circuits).
            assertThat(router.embeddingCalls()).isEqualTo(97);
            ScoredExample structural = scored.get(1);
            assertThat(structural.structuralFired()).isTrue();
            assertThat(structural.semanticScores()).isNull();
            assertThat(structural.signals())
                    .contains(StructuralRoutingSignal.MULTI_ORDER_REFERENCE);
            RoutingDecision decision = router.decide(structural, RoutingStrategy.HYBRID,
                    hybridProperties().semantic());
            assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
            assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_MULTI_ORDER_REFERENCE);
        }
    }

    @Test
    void nonSemanticStrategiesNeedNoEmbeddingService() {
        // Allows exactly the 96 prototype embeddings, then fails: proves no query
        // embedding is spent outside the HYBRID semantic path.
        RoutingEmbeddingService strict = new RoutingEmbeddingService() {
            private final java.util.concurrent.atomic.AtomicLong calls =
                    new java.util.concurrent.atomic.AtomicLong();
            private final StubEmbeddingService stub = new StubEmbeddingService();

            @Override
            public float[] embed(String text) {
                if (calls.incrementAndGet() > 96) {
                    throw new RoutingInferenceException("query embedding must not run here");
                }
                return stub.embed(text);
            }
        };
        try (Phase3Router router = Phase3Router.create(hybridProperties(), strict)) {
            assertThat(router.embeddingCalls()).isEqualTo(96);
            EvalExample structural = example("VAL-EN-T2-001", ModelTier.TIER_2,
                    "Cancel ORD-48210 and change the address for ORD-48211.");
            List<ScoredExample> scored = router.scoreForHybrid(List.of(structural));
            // Structural short-circuit: still no query embedding.
            assertThat(router.embeddingCalls()).isEqualTo(96);
            assertThat(scored.get(0).semanticScores()).isNull();

            SemanticRoutingProperties semantic = hybridProperties().semantic();
            assertThat(router.decide(scored.get(0), RoutingStrategy.RULE_ONLY, semantic).tier())
                    .isEqualTo(ModelTier.TIER_2);
            assertThat(router.decide(scored.get(0), RoutingStrategy.ALWAYS_TIER_1, semantic).tier())
                    .isEqualTo(ModelTier.TIER_1);
            assertThat(router.decide(scored.get(0), RoutingStrategy.ALWAYS_TIER_2, semantic).tier())
                    .isEqualTo(ModelTier.TIER_2);
            assertThat(router.embeddingCalls()).isEqualTo(96);
        }
    }
}
