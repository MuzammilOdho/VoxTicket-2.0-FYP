package com.voxticket.routing;

/**
 * Phase 2: per-turn semantic class scores.
 *
 * @param simpleScore  mean of the top-K cosine similarities against the TIER_1 prototypes.
 * @param complexScore mean of the top-K cosine similarities against the TIER_2 prototypes.
 * @param margin       {@code complexScore - simpleScore}: positive leans complex, negative
 *                     leans simple. Compared against the (currently placeholder, uncalibrated)
 *                     margins in {@link SemanticRoutingProperties}; calibration is Phase 3.
 */
public record SemanticScores(double simpleScore, double complexScore, double margin) {
}
