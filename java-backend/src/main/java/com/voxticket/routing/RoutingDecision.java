package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import java.util.Set;

/**
 * Phase 2: the router's full internal decision.
 *
 * <p>Only {@link #tier()} crosses the provider boundary (via {@code ModelSelectionResult}); the
 * scores, margin and signals stay inside the routing layer for logging, metrics and experiments.
 * Non-semantic decisions (forced strategies, pure rules) carry {@code NaN} scores - metrics
 * recording must skip {@code NaN} rather than recording meaningless zeros.
 */
public record RoutingDecision(
        ModelTier tier,
        RoutingReason reason,
        double simpleScore,
        double complexScore,
        double margin,
        Set<StructuralRoutingSignal> signals) {

    /** A decision made without any semantic scoring (forced strategy or pure rule). */
    public static RoutingDecision withoutScores(ModelTier tier, RoutingReason reason, Set<StructuralRoutingSignal> signals) {
        return new RoutingDecision(tier, reason, Double.NaN, Double.NaN, Double.NaN, Set.copyOf(signals));
    }

    /** True when this decision involved semantic class scoring (HYBRID semantic path). */
    public boolean hasSemanticScores() {
        return !Double.isNaN(margin);
    }
}
