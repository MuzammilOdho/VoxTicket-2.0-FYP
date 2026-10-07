package com.voxticket.agent;

import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingStrategy;

/**
 * The tier plus WHY it was chosen - captures "routing reason" for observability without needing a separate lookup.
 *
 * <p>P2: also carries the full {@link RoutingDecision} (semantic scores,
 * margin, structural signals) and the {@link RoutingStrategy} that produced
 * it, so the turn decision trace can record them without a second routing
 * pass. Both are null for hand-built stubs that never ran the router.
 */
public record ModelSelectionResult(ModelTier tier, String reason, RoutingDecision routingDecision, RoutingStrategy strategy) {

    /** Legacy shape for stubs/tests that never ran the router. */
    public ModelSelectionResult(ModelTier tier, String reason) {
        this(tier, reason, null, null);
    }
}
