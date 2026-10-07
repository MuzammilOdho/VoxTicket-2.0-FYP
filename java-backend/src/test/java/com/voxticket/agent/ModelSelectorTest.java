package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingExampleIndex;
import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import com.voxticket.routing.SemanticRoutingService;
import com.voxticket.routing.StructuralFeatureExtractor;
import com.voxticket.routing.StructuralRoutingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Phase 2: the tier router across all four strategies. Semantic legs use stub embeddings -
 * never the ONNX model - so these tests are hermetic.
 */
class ModelSelectorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TurnMetrics turnMetrics = new TurnMetrics(registry);
    private final StructuralRoutingProperties structural = new StructuralRoutingProperties(300, 2);
    private final SemanticRoutingProperties semantic =
            new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "",
                    "classpath:routing/examples-v1.json", 3, 0.02, 0.02);
    private final StructuralFeatureExtractor extractor =
            new StructuralFeatureExtractor(new RoutingProperties(RoutingStrategy.RULE_ONLY, structural, semantic));
    private final RoutingDecisionEngine engine = new RoutingDecisionEngine();

    private ModelSelector selector(RoutingStrategy strategy, Optional<SemanticRoutingService> semanticService) {
        return new ModelSelector(new RoutingProperties(strategy, structural, semantic),
                extractor, engine, semanticService, turnMetrics);
    }

    private static float[] unit(double... values) {
        float[] out = new float[RoutingEmbeddingService.DIMENSION];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        double norm = 0;
        for (float x : out) {
            norm += x * x;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= Math.sqrt(norm);
        }
        return out;
    }

    /** Stub semantic service whose query embedding leans fully toward the given tier. */
    private SemanticRoutingService stubSemantic(ModelTier toward, AtomicInteger calls) {
        RoutingEmbeddingService embedding = text -> {
            calls.incrementAndGet();
            return toward == ModelTier.TIER_1 ? unit(1, 0, 0) : unit(0, 1, 0);
        };
        var index = RoutingExampleIndex.fromVectors(Map.of(
                ModelTier.TIER_1, List.of(unit(1, 0, 0)),
                ModelTier.TIER_2, List.of(unit(0, 1, 0))));
        return new SemanticRoutingService(embedding, index, semantic);
    }

    private static ConversationSession session() {
        return ConversationSession.newSession("s1", Channel.CHAT);
    }

    @Test
    void ruleOnlyShortSimpleMessageStaysTier1() {
        var result = selector(RoutingStrategy.RULE_ONLY, Optional.empty())
                .select(session(), "Where is my order?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(result.reason()).isEqualTo("RULE_DEFAULT");
    }

    @Test
    void ruleOnlyLongMessageEscalates() {
        var result = selector(RoutingStrategy.RULE_ONLY, Optional.empty())
                .select(session(), "I need help with my order. ".repeat(20));

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("RULE_LONG_COMPOUND_MESSAGE");
    }

    @Test
    void ruleOnlyMultiOrderReferenceEscalates() {
        var result = selector(RoutingStrategy.RULE_ONLY, Optional.empty())
                .select(session(), "What's happening with ORD-10001 and also ORD-10002?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("RULE_MULTI_ORDER_REFERENCE");
    }

    @Test
    void englishConditionalMarkerIsNoLongerASignal() {
        // Phase 1's English-only "unless/if" heuristic is gone on purpose: the semantic tier
        // handles conditional complexity across languages instead.
        var result = selector(RoutingStrategy.RULE_ONLY, Optional.empty())
                .select(session(), "Cancel my order unless it has already shipped.");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(result.reason()).isEqualTo("RULE_DEFAULT");
    }

    @Test
    void alwaysTier1ForcesTier1() {
        var result = selector(RoutingStrategy.ALWAYS_TIER_1, Optional.empty())
                .select(session(), "What's happening with ORD-10001 and also ORD-10002?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(result.reason()).isEqualTo("FORCED_TIER_1");
    }

    @Test
    void alwaysTier2ForcesTier2() {
        var result = selector(RoutingStrategy.ALWAYS_TIER_2, Optional.empty())
                .select(session(), "Where is my order?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("FORCED_TIER_2");
    }

    @Test
    void hybridStructuralSignalShortCircuitsWithoutEmbedding() {
        AtomicInteger calls = new AtomicInteger();

        var result = selector(RoutingStrategy.HYBRID, Optional.of(stubSemantic(ModelTier.TIER_1, calls)))
                .select(session(), "What's happening with ORD-10001 and also ORD-10002?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("RULE_MULTI_ORDER_REFERENCE");
        assertThat(calls.get()).isZero();
    }

    @Test
    void hybridSemanticSimpleRoutesTier1WithOneEmbedding() {
        AtomicInteger calls = new AtomicInteger();

        var result = selector(RoutingStrategy.HYBRID, Optional.of(stubSemantic(ModelTier.TIER_1, calls)))
                .select(session(), "Where is my order?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(result.reason()).isEqualTo("SEMANTIC_SIMPLE");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void hybridSemanticComplexRoutesTier2WithOneEmbedding() {
        AtomicInteger calls = new AtomicInteger();

        var result = selector(RoutingStrategy.HYBRID, Optional.of(stubSemantic(ModelTier.TIER_2, calls)))
                .select(session(), "Where is my order?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("SEMANTIC_COMPLEX");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void selectionResultCarriesTierReasonDecisionAndStrategy() {
        var result = selector(RoutingStrategy.RULE_ONLY, Optional.empty())
                .selectDetailed(session(), "Where is my order?");

        assertThat(result).isInstanceOf(ModelSelectionResult.class);
        assertThat(ModelSelectionResult.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("tier", "reason", "routingDecision", "strategy");
        assertThat(result.routingDecision()).isNotNull();
        assertThat(result.routingDecision().tier()).isEqualTo(result.tier());
        assertThat(result.strategy()).isEqualTo(RoutingStrategy.RULE_ONLY);
    }

    @Test
    void selectDelegatesToSelectDetailed() {
        var selector = selector(RoutingStrategy.RULE_ONLY, Optional.empty());

        var viaSelect = selector.select(session(), "Where is my order?");
        var viaDetailed = selector.selectDetailed(session(), "Where is my order?");

        assertThat(viaSelect.tier()).isEqualTo(viaDetailed.tier());
        assertThat(viaSelect.reason()).isEqualTo(viaDetailed.reason());
        assertThat(viaSelect.routingDecision()).isEqualTo(viaDetailed.routingDecision());
    }

    @Test
    void selectorExposesNoProviderOrModelLookup() {
        assertThat(java.util.Arrays.stream(ModelSelector.class.getMethods()).map(m -> m.getName()))
                .doesNotContain("modelFor", "providerFor", "model", "provider");
    }

    @Test
    void routingDecisionIsRecordedWithBoundedTags() {
        selector(RoutingStrategy.RULE_ONLY, Optional.empty()).select(session(), "Where is my order?");

        var counter = registry.find("voxticket.routing.decision")
                .tags("strategy", "RULE_ONLY", "tier", "TIER_1", "reason", "RULE_DEFAULT")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void semanticMarginIsRecordedButRuleOnlySkipsIt() {
        AtomicInteger calls = new AtomicInteger();
        selector(RoutingStrategy.RULE_ONLY, Optional.empty()).select(session(), "Where is my order?");
        selector(RoutingStrategy.HYBRID, Optional.of(stubSemantic(ModelTier.TIER_1, calls)))
                .select(session(), "Where is my order?");

        assertThat(registry.find("voxticket.routing.margin").tag("strategy", "HYBRID").summary())
                .isNotNull();
        assertThat(registry.find("voxticket.routing.margin").tag("strategy", "RULE_ONLY").summary())
                .isNull();
    }
}
