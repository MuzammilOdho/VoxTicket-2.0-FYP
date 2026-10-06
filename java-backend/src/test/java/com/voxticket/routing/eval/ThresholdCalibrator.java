package com.voxticket.routing.eval;

import com.voxticket.agent.ModelTier;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Phase 3: threshold calibration on the VALIDATION set only.
 *
 * <p>Decision-semantics finding (verified against {@link RoutingDecisionEngine}):
 * with no structural signal, HYBRID routes TIER_1 iff
 * {@code margin <= -simpleMargin} and TIER_2 otherwise. {@code complexMargin}
 * only renames the TIER_2 reason ({@code SEMANTIC_COMPLEX} vs
 * {@code SEMANTIC_AMBIGUOUS}) - it cannot change tier accuracy and is therefore
 * NOT calibrated here; it stays at its Phase 2 value.
 *
 * <p>Candidate thresholds are derived from the observed validation margins
 * (midpoints between consecutive sorted unique margins), plus 0.0, the current
 * value, and the all-TIER_2 extreme - i.e. every meaningful decision boundary.
 *
 * <p>Selection objective: maximize balanced accuracy; ties broken by
 * (1) lower under-routing, (2) higher macro-F1, (3) lower TIER_2 selection rate.
 * Candidates are examined in ascending order and replaced only on strict
 * improvement, so selection is fully deterministic.
 */
public final class ThresholdCalibrator {

    private ThresholdCalibrator() {
    }

    /** One evaluated candidate threshold. */
    public record CandidateRow(double simpleMargin, RoutingMetrics.Metrics metrics) {
    }

    /** The frozen calibration outcome. */
    public record CalibrationResult(
            double selectedSimpleMargin,
            List<CandidateRow> candidateRows,
            RoutingMetrics.Metrics beforeMetrics,
            RoutingMetrics.Metrics afterMetrics) {
    }

    public static CalibrationResult calibrate(
            List<ScoredExample> validation,
            RoutingDecisionEngine engine,
            SemanticRoutingProperties baseProps) {
        // Hard guard: the held-out FINAL_TEST split must never influence calibration.
        for (ScoredExample scored : validation) {
            if (scored.split() == EvalSplit.FINAL_TEST) {
                throw new IllegalArgumentException(
                        "calibration accepts the validation split only; got FINAL_TEST example "
                                + scored.exampleId());
            }
        }
        TreeSet<Double> margins = new TreeSet<>();
        for (ScoredExample scored : validation) {
            if (scored.semanticScores() != null) {
                margins.add(scored.semanticScores().margin());
            }
        }

        TreeSet<Double> candidates = new TreeSet<>();
        candidates.add(0.0);
        candidates.add(baseProps.simpleMargin());
        Double previous = null;
        for (Double margin : margins) {
            if (previous != null) {
                double midpoint = (previous + margin) / 2.0;
                // simpleMargin is a non-negative tolerance knob: negative midpoints would
                // extend the TIER_1 region into positive margins, outside the knob's range.
                if (midpoint >= 0.0) {
                    candidates.add(midpoint);
                }
            }
            previous = margin;
        }
        if (!margins.isEmpty()) {
            // The all-semantic-TIER_2 extreme: beyond the largest observed margin.
            candidates.add(margins.last() + 0.01);
        }

        List<CandidateRow> rows = new ArrayList<>();
        CandidateRow best = null;
        for (double candidate : candidates) {
            RoutingMetrics.Metrics metrics = evaluateAt(validation, engine, baseProps, candidate);
            CandidateRow row = new CandidateRow(candidate, metrics);
            rows.add(row);
            if (best == null || strictlyBetter(metrics, best.metrics())) {
                best = row;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Cannot calibrate on an empty validation set");
        }
        RoutingMetrics.Metrics before =
                evaluateAt(validation, engine, baseProps, baseProps.simpleMargin());
        RoutingMetrics.Metrics after =
                evaluateAt(validation, engine, baseProps, best.simpleMargin());
        return new CalibrationResult(best.simpleMargin(), rows, before, after);
    }

    /**
     * HYBRID tier predictions at one candidate {@code simpleMargin}, using the
     * production {@link RoutingDecisionEngine#decide} - no reimplementation, the
     * stored margins are simply re-decided under different knobs.
     */
    public static RoutingMetrics.Metrics evaluateAt(
            List<ScoredExample> validation,
            RoutingDecisionEngine engine,
            SemanticRoutingProperties baseProps,
            double simpleMargin) {
        SemanticRoutingProperties props = new SemanticRoutingProperties(
                baseProps.enabled(), baseProps.model(), baseProps.modelPath(),
                baseProps.examples(), baseProps.topKPerClass(),
                baseProps.complexMargin(), simpleMargin);
        List<RoutingMetrics.Prediction> predictions = new ArrayList<>(validation.size());
        for (ScoredExample scored : validation) {
            RoutingDecision decision =
                    engine.decide(RoutingStrategy.HYBRID, scored.signals(),
                            scored.semanticScores(), props);
            predictions.add(new RoutingMetrics.Prediction(
                    scored.example().expectedTier(), decision.tier()));
        }
        return RoutingMetrics.compute(predictions);
    }

    /**
     * Lexicographic objective: higher balanced accuracy, then lower under-routing,
     * then higher macro-F1, then lower TIER_2 selection rate. NaN balanced accuracy
     * (degenerate prediction set) sorts below every real value.
     */
    static boolean strictlyBetter(RoutingMetrics.Metrics candidate, RoutingMetrics.Metrics best) {
        double candidateBalanced = nanToWorst(candidate.balancedAccuracy());
        double bestBalanced = nanToWorst(best.balancedAccuracy());
        if (candidateBalanced != bestBalanced) {
            return candidateBalanced > bestBalanced;
        }
        if (candidate.underRouted() != best.underRouted()) {
            return candidate.underRouted() < best.underRouted();
        }
        double candidateMacro = nanToWorst(candidate.macroF1());
        double bestMacro = nanToWorst(best.macroF1());
        if (candidateMacro != bestMacro) {
            return candidateMacro > bestMacro;
        }
        double candidateT2 = nanToWorst(candidate.t2SelectionRate());
        double bestT2 = nanToWorst(best.t2SelectionRate());
        // Lower TIER_2 usage wins; NaN (empty set) can only occur for n=0, already excluded.
        return candidateT2 < bestT2;
    }

    private static double nanToWorst(double value) {
        return Double.isNaN(value) ? -1.0 : value;
    }
}
