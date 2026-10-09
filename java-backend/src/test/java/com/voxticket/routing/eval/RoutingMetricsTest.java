package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.voxticket.agent.ModelTier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: metric-calculation regression tests against hand-computed fixtures.
 * Hermetic - pure math, no model.
 */
class RoutingMetricsTest {

    private static RoutingMetrics.Prediction pred(ModelTier expected, ModelTier predicted) {
        return new RoutingMetrics.Prediction(expected, predicted);
    }

    @Test
    void confusionMatrixAndCoreMetrics() {
        // tp=3, fn=1, fp=2, tn=4, n=10
        List<RoutingMetrics.Prediction> predictions = List.of(
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_2, ModelTier.TIER_1),
                pred(ModelTier.TIER_1, ModelTier.TIER_2),
                pred(ModelTier.TIER_1, ModelTier.TIER_2),
                pred(ModelTier.TIER_1, ModelTier.TIER_1),
                pred(ModelTier.TIER_1, ModelTier.TIER_1),
                pred(ModelTier.TIER_1, ModelTier.TIER_1),
                pred(ModelTier.TIER_1, ModelTier.TIER_1));
        RoutingMetrics.Metrics m = RoutingMetrics.compute(predictions);

        assertThat(m.n()).isEqualTo(10);
        assertThat(m.confusionMatrix()).isEqualTo(new RoutingMetrics.ConfusionMatrix(3, 2, 4, 1));
        assertThat(m.accuracy()).isCloseTo(0.7, within(1e-9));
        // balanced = (tpr + tnr)/2 = (3/4 + 4/6)/2
        assertThat(m.balancedAccuracy()).isCloseTo((0.75 + 4.0 / 6.0) / 2.0, within(1e-9));
        // t2: p=3/5, r=3/4 -> f1 = 2*.6*.75/1.35
        assertThat(m.t2Precision()).isCloseTo(0.6, within(1e-9));
        assertThat(m.t2Recall()).isCloseTo(0.75, within(1e-9));
        assertThat(m.t2F1()).isCloseTo(2 * 0.6 * 0.75 / 1.35, within(1e-9));
        // t1: p=4/5, r=4/6
        assertThat(m.t1Precision()).isCloseTo(0.8, within(1e-9));
        assertThat(m.t1Recall()).isCloseTo(4.0 / 6.0, within(1e-9));
        double t1F1 = 2 * 0.8 * (4.0 / 6.0) / (0.8 + 4.0 / 6.0);
        assertThat(m.t1F1()).isCloseTo(t1F1, within(1e-9));
        assertThat(m.macroF1()).isCloseTo((m.t2F1() + t1F1) / 2.0, within(1e-9));
    }

    @Test
    void underAndOverRoutingDefinitions() {
        List<RoutingMetrics.Prediction> predictions = List.of(
                pred(ModelTier.TIER_2, ModelTier.TIER_1), // under
                pred(ModelTier.TIER_2, ModelTier.TIER_1), // under
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_1, ModelTier.TIER_2), // over
                pred(ModelTier.TIER_1, ModelTier.TIER_1));
        RoutingMetrics.Metrics m = RoutingMetrics.compute(predictions);

        assertThat(m.underRouted()).isEqualTo(2);
        assertThat(m.underRouteRate()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(m.overRouted()).isEqualTo(1);
        assertThat(m.overRouteRate()).isCloseTo(1.0 / 2.0, within(1e-9));
        assertThat(m.t2SelectionRate()).isCloseTo(2.0 / 5.0, within(1e-9));
    }

    @Test
    void perfectPredictions() {
        List<RoutingMetrics.Prediction> predictions = List.of(
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_1, ModelTier.TIER_1));
        RoutingMetrics.Metrics m = RoutingMetrics.compute(predictions);
        assertThat(m.accuracy()).isCloseTo(1.0, within(1e-9));
        assertThat(m.balancedAccuracy()).isCloseTo(1.0, within(1e-9));
        assertThat(m.macroF1()).isCloseTo(1.0, within(1e-9));
        assertThat(m.underRouted()).isZero();
        assertThat(m.overRouted()).isZero();
    }

    @Test
    void degenerateSingleClassPredictionsUseZeroDivisionConvention() {
        List<RoutingMetrics.Prediction> predictions = List.of(
                pred(ModelTier.TIER_2, ModelTier.TIER_2),
                pred(ModelTier.TIER_2, ModelTier.TIER_2));
        RoutingMetrics.Metrics m = RoutingMetrics.compute(predictions);
        // No TIER_1 examples at all: TIER_1 recall is 0/0 -> 0.0 by convention; must not throw.
        assertThat(m.t2Recall()).isCloseTo(1.0, within(1e-9));
        assertThat(m.t1Recall()).isCloseTo(0.0, within(1e-9));
        assertThat(m.t1F1()).isCloseTo(0.0, within(1e-9));
        assertThat(m.macroF1()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void constantPredictorMacroF1IsOneThirdOnBalancedClasses() {
        // ALWAYS_TIER_1 on a balanced set: the degenerate class contributes F1=0,
        // the predicted class contributes F1=2/3 -> macro-F1 = 1/3, not NaN.
        List<RoutingMetrics.Prediction> predictions = List.of(
                pred(ModelTier.TIER_1, ModelTier.TIER_1),
                pred(ModelTier.TIER_2, ModelTier.TIER_1));
        RoutingMetrics.Metrics m = RoutingMetrics.compute(predictions);
        assertThat(m.t2Precision()).isCloseTo(0.0, within(1e-9));
        assertThat(m.t2Recall()).isCloseTo(0.0, within(1e-9));
        assertThat(m.t2F1()).isCloseTo(0.0, within(1e-9));
        assertThat(m.t1F1()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(m.macroF1()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(m.accuracy()).isCloseTo(0.5, within(1e-9));
        assertThat(m.balancedAccuracy()).isCloseTo(0.5, within(1e-9));
    }
}
