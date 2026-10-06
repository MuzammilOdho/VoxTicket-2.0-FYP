package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.voxticket.agent.ModelTier;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingReason;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import com.voxticket.routing.SemanticScores;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: calibration regression tests. Hermetic - synthetic margins, the real
 * production {@link RoutingDecisionEngine}, no ONNX model.
 */
class ThresholdCalibratorTest {

    private static final SemanticRoutingProperties BASE_PROPS = new SemanticRoutingProperties(
            true, "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json",
            3, 0.02, 0.02);
    private static final RoutingDecisionEngine ENGINE = new RoutingDecisionEngine();

    private static ScoredExample semantic(String id, ModelTier expected, double margin) {
        EvalExample example = new EvalExample(id, EvalSplit.VALIDATION, EvalLanguage.ENGLISH,
                expected, "text " + id, List.of("test"), "synthetic");
        double simple = margin < 0 ? -margin : 0.0;
        double complex = margin > 0 ? margin : 0.0;
        return new ScoredExample(example, Set.of(), new SemanticScores(simple, complex, margin));
    }

    @Test
    void selectsMarginDerivedFromObservedValidationMargins() {
        // Margins: T2 +0.10, T1 -0.10, T2 +0.005, T1 -0.005. At the 0.02 placeholder the
        // -0.005 TIER_1 example falls in the ambiguous band (over-routed); s=0.0 fixes it.
        List<ScoredExample> validation = List.of(
                semantic("s1", ModelTier.TIER_2, 0.10),
                semantic("s2", ModelTier.TIER_1, -0.10),
                semantic("s3", ModelTier.TIER_2, 0.005),
                semantic("s4", ModelTier.TIER_1, -0.005));
        ThresholdCalibrator.CalibrationResult result =
                ThresholdCalibrator.calibrate(validation, ENGINE, BASE_PROPS);

        assertThat(result.selectedSimpleMargin()).isCloseTo(0.0, within(1e-12));
        assertThat(result.afterMetrics().balancedAccuracy()).isCloseTo(1.0, within(1e-9));
        assertThat(result.beforeMetrics().balancedAccuracy()).isLessThan(1.0);
        // The winning candidate must be one of the derived boundaries, not a magic number.
        assertThat(result.candidateRows().stream()
                .map(ThresholdCalibrator.CandidateRow::simpleMargin))
                .contains(0.0, 0.02);
        assertThat(result.candidateRows().stream()
                .map(ThresholdCalibrator.CandidateRow::simpleMargin))
                .allMatch(c -> c >= 0.0);
    }

    @Test
    void calibrationIsDeterministic() {
        List<ScoredExample> validation = List.of(
                semantic("s1", ModelTier.TIER_2, 0.10),
                semantic("s2", ModelTier.TIER_1, -0.10),
                semantic("s3", ModelTier.TIER_2, 0.005),
                semantic("s4", ModelTier.TIER_1, -0.005));
        ThresholdCalibrator.CalibrationResult first =
                ThresholdCalibrator.calibrate(validation, ENGINE, BASE_PROPS);
        ThresholdCalibrator.CalibrationResult second =
                ThresholdCalibrator.calibrate(validation, ENGINE, BASE_PROPS);
        assertThat(second.selectedSimpleMargin())
                .isCloseTo(first.selectedSimpleMargin(), within(1e-12));
        assertThat(second.candidateRows()).hasSize(first.candidateRows().size());
    }

    private static RoutingMetrics.Metrics metrics(double balanced, long under, double macro, double t2rate) {
        return new RoutingMetrics.Metrics(10, new RoutingMetrics.ConfusionMatrix(0, 0, 0, 0),
                0.0, balanced, macro,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                under, 0.0, 0, 0.0, t2rate);
    }

    @Test
    void tieBreakPrefersLowerUnderRouting() {
        RoutingMetrics.Metrics higherUnder = metrics(0.8, 2, 0.7, 0.5);
        RoutingMetrics.Metrics lowerUnder = metrics(0.8, 1, 0.7, 0.5);
        assertThat(ThresholdCalibrator.strictlyBetter(lowerUnder, higherUnder)).isTrue();
        assertThat(ThresholdCalibrator.strictlyBetter(higherUnder, lowerUnder)).isFalse();
    }

    @Test
    void tieBreakPrefersHigherMacroF1() {
        RoutingMetrics.Metrics lowerMacro = metrics(0.8, 1, 0.6, 0.5);
        RoutingMetrics.Metrics higherMacro = metrics(0.8, 1, 0.7, 0.5);
        assertThat(ThresholdCalibrator.strictlyBetter(higherMacro, lowerMacro)).isTrue();
        assertThat(ThresholdCalibrator.strictlyBetter(lowerMacro, higherMacro)).isFalse();
    }

    @Test
    void tieBreakPrefersLowerTier2SelectionRate() {
        RoutingMetrics.Metrics higherRate = metrics(0.8, 1, 0.7, 0.6);
        RoutingMetrics.Metrics lowerRate = metrics(0.8, 1, 0.7, 0.5);
        assertThat(ThresholdCalibrator.strictlyBetter(lowerRate, higherRate)).isTrue();
        assertThat(ThresholdCalibrator.strictlyBetter(higherRate, lowerRate)).isFalse();
    }

    @Test
    void complexMarginDoesNotAffectTierSelection() {
        // Decision-semantics finding, pinned: complexMargin only renames the TIER_2
        // reason (SEMANTIC_COMPLEX vs SEMANTIC_AMBIGUOUS); the tier is unchanged.
        ScoredExample scored = semantic("s1", ModelTier.TIER_2, 0.5);
        SemanticRoutingProperties lowComplex = new SemanticRoutingProperties(
                true, "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json",
                3, 0.02, 0.02);
        SemanticRoutingProperties highComplex = new SemanticRoutingProperties(
                true, "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json",
                3, 0.99, 0.02);
        RoutingDecision low = ENGINE.decide(RoutingStrategy.HYBRID,
                scored.signals(), scored.semanticScores(), lowComplex);
        RoutingDecision high = ENGINE.decide(RoutingStrategy.HYBRID,
                scored.signals(), scored.semanticScores(), highComplex);
        assertThat(low.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(high.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(low.reason()).isEqualTo(RoutingReason.SEMANTIC_COMPLEX);
        assertThat(high.reason()).isEqualTo(RoutingReason.SEMANTIC_AMBIGUOUS);
    }

    @Test
    void rejectsFinalTestExamples() {
        // Regression guard: the held-out FINAL_TEST split must never influence calibration.
        EvalExample leaked = new EvalExample("TST-EN-T1-001", EvalSplit.FINAL_TEST,
                EvalLanguage.ENGLISH, ModelTier.TIER_1, "text", List.of("test"), "synthetic");
        ScoredExample scored = new ScoredExample(leaked, Set.of(),
                new SemanticScores(0.0, 0.1, 0.1));
        assertThatThrownBy(() -> ThresholdCalibrator.calibrate(List.of(scored), ENGINE, BASE_PROPS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FINAL_TEST");
    }
}
