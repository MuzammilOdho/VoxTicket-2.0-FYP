package com.voxticket.routing.eval;

import com.voxticket.agent.ModelTier;
import java.util.List;

/**
 * Phase 3: pure, deterministic routing-classification metrics. TIER_2 is the
 * positive class throughout.
 *
 * <p>Definitions (fixed by the Phase 3 task):
 * <ul>
 *   <li>UNDER-ROUTING: expected TIER_2 but predicted TIER_1;</li>
 *   <li>OVER-ROUTING: expected TIER_1 but predicted TIER_2.</li>
 * </ul>
 */
public final class RoutingMetrics {

    private RoutingMetrics() {
    }

    /** One expected-vs-predicted tier pair. */
    public record Prediction(ModelTier expected, ModelTier predicted) {
    }

    /** Confusion matrix with TIER_2 as the positive class. */
    public record ConfusionMatrix(long tp, long fp, long tn, long fn) {
    }

    /** All Phase 3 metrics for one prediction set. Zero-division ratios use the
     * zero_division=0 convention (0.0), so constant predictors get mathematically
     * valid values (e.g. macro-F1 = 1/3 for a constant predictor on balanced
     * classes) instead of NaN. Only accuracy and t2SelectionRate stay NaN, and
     * only for an empty prediction set. */
    public record Metrics(
            long n,
            ConfusionMatrix confusionMatrix,
            double accuracy,
            double balancedAccuracy,
            double macroF1,
            double t2Precision,
            double t2Recall,
            double t2F1,
            double t1Precision,
            double t1Recall,
            double t1F1,
            long underRouted,
            double underRouteRate,
            long overRouted,
            double overRouteRate,
            double t2SelectionRate) {
    }

    public static Metrics compute(List<Prediction> predictions) {
        long tp = 0, fp = 0, tn = 0, fn = 0;
        for (Prediction p : predictions) {
            boolean expectedT2 = p.expected() == ModelTier.TIER_2;
            boolean predictedT2 = p.predicted() == ModelTier.TIER_2;
            if (expectedT2 && predictedT2) {
                tp++;
            } else if (!expectedT2 && predictedT2) {
                fp++;
            } else if (!expectedT2) {
                tn++;
            } else {
                fn++;
            }
        }
        long n = tp + fp + tn + fn;

        double t2Precision = div(tp, tp + fp);
        double t2Recall = div(tp, tp + fn);
        double t2F1 = f1(t2Precision, t2Recall);
        // TIER_1 as the positive class: precision/recall swap roles of fp/fn.
        double t1Precision = div(tn, tn + fn);
        double t1Recall = div(tn, tn + fp);
        double t1F1 = f1(t1Precision, t1Recall);

        double accuracy = n == 0 ? Double.NaN : (double) (tp + tn) / n;
        double balancedAccuracy = (t2Recall + t1Recall) / 2.0;
        double macroF1 = (t2F1 + t1F1) / 2.0;

        long underRouted = fn;
        long overRouted = fp;
        double underRouteRate = div(fn, tp + fn);
        double overRouteRate = div(fp, tn + fp);
        double t2SelectionRate = n == 0 ? Double.NaN : (double) (tp + fp) / n;

        return new Metrics(n, new ConfusionMatrix(tp, fp, tn, fn),
                accuracy, balancedAccuracy, macroF1,
                t2Precision, t2Recall, t2F1, t1Precision, t1Recall, t1F1,
                underRouted, underRouteRate, overRouted, overRouteRate, t2SelectionRate);
    }

    private static double f1(double precision, double recall) {
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }

    private static double div(long num, long den) {
        return den == 0 ? 0.0 : (double) num / den;
    }
}
