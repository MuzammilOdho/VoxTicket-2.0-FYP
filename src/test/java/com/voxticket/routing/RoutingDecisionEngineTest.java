package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: the decision engine is a pure function of (strategy, signals, semantic scores).
 * Margins here use the placeholder defaults (0.02); calibration is Phase 3.
 */
class RoutingDecisionEngineTest {

    private final RoutingDecisionEngine engine = new RoutingDecisionEngine();
    private final SemanticRoutingProperties semantic =
            new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json", 3, 0.02, 0.02);

    private static final Set<StructuralRoutingSignal> NO_SIGNALS = Set.of();

    @Test
    void alwaysTier1IgnoresEverything() {
        var signals = EnumSet.allOf(StructuralRoutingSignal.class);

        RoutingDecision decision = engine.decide(RoutingStrategy.ALWAYS_TIER_1, signals, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(decision.reason()).isEqualTo(RoutingReason.FORCED_TIER_1);
        assertThat(decision.hasSemanticScores()).isFalse();
    }

    @Test
    void alwaysTier2IgnoresEverything() {
        RoutingDecision decision = engine.decide(RoutingStrategy.ALWAYS_TIER_2, NO_SIGNALS, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.FORCED_TIER_2);
    }

    @Test
    void ruleOnlyMultiOrderWinsOverOtherSignals() {
        var signals = EnumSet.allOf(StructuralRoutingSignal.class);

        RoutingDecision decision = engine.decide(RoutingStrategy.RULE_ONLY, signals, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_MULTI_ORDER_REFERENCE);
    }

    @Test
    void ruleOnlyLongMessageBeatsSessionState() {
        var signals = EnumSet.of(StructuralRoutingSignal.LONG_COMPOUND_MESSAGE,
                StructuralRoutingSignal.COMPLEX_SESSION_STATE);

        RoutingDecision decision = engine.decide(RoutingStrategy.RULE_ONLY, signals, null, semantic);

        assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_LONG_COMPOUND_MESSAGE);
    }

    @Test
    void ruleOnlyComplexSessionState() {
        var signals = Set.of(StructuralRoutingSignal.COMPLEX_SESSION_STATE);

        RoutingDecision decision = engine.decide(RoutingStrategy.RULE_ONLY, signals, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_COMPLEX_SESSION_STATE);
    }

    @Test
    void ruleOnlyDefaultsToTier1() {
        RoutingDecision decision = engine.decide(RoutingStrategy.RULE_ONLY, NO_SIGNALS, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_DEFAULT);
        assertThat(decision.hasSemanticScores()).isFalse();
        assertThat(decision.signals()).isEmpty();
    }

    @Test
    void hybridStructuralSignalsShortCircuitWithoutSemanticScores() {
        var signals = Set.of(StructuralRoutingSignal.MULTI_ORDER_REFERENCE);

        // Even with strong semantic-simple scores present, the structural signal wins and no
        // semantic scores are attached (the caller skips the embedding entirely).
        RoutingDecision decision = engine.decide(RoutingStrategy.HYBRID, signals, null, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.RULE_MULTI_ORDER_REFERENCE);
        assertThat(decision.hasSemanticScores()).isFalse();
    }

    @Test
    void hybridClearComplexMarginRoutesTier2() {
        var scores = new SemanticScores(0.30, 0.60, 0.30);

        RoutingDecision decision = engine.decide(RoutingStrategy.HYBRID, NO_SIGNALS, scores, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.SEMANTIC_COMPLEX);
        assertThat(decision.margin()).isEqualTo(0.30);
        assertThat(decision.hasSemanticScores()).isTrue();
    }

    @Test
    void hybridClearSimpleMarginRoutesTier1() {
        var scores = new SemanticScores(0.60, 0.30, -0.30);

        RoutingDecision decision = engine.decide(RoutingStrategy.HYBRID, NO_SIGNALS, scores, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(decision.reason()).isEqualTo(RoutingReason.SEMANTIC_SIMPLE);
    }

    @Test
    void hybridAmbiguousMarginFallsBackToTier2Conservatively() {
        var scores = new SemanticScores(0.50, 0.505, 0.005);

        RoutingDecision decision = engine.decide(RoutingStrategy.HYBRID, NO_SIGNALS, scores, semantic);

        assertThat(decision.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(decision.reason()).isEqualTo(RoutingReason.SEMANTIC_AMBIGUOUS);
    }

    @Test
    void hybridExactlyAtMarginBoundaryCountsAsClear() {
        var scores = new SemanticScores(0.50, 0.52, 0.02);

        RoutingDecision decision = engine.decide(RoutingStrategy.HYBRID, NO_SIGNALS, scores, semantic);

        assertThat(decision.reason()).isEqualTo(RoutingReason.SEMANTIC_COMPLEX);
    }

    @Test
    void hybridWithoutSemanticScoresFailsLoudly() {
        assertThatThrownBy(() -> engine.decide(RoutingStrategy.HYBRID, NO_SIGNALS, null, semantic))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without semantic scores");
    }

    @Test
    void nonSemanticDecisionsCarryNaNScores() {
        RoutingDecision decision = engine.decide(RoutingStrategy.RULE_ONLY, NO_SIGNALS, null, semantic);

        assertThat(decision.simpleScore()).isNaN();
        assertThat(decision.complexScore()).isNaN();
        assertThat(decision.margin()).isNaN();
    }
}
