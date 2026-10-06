package com.voxticket.routing;

/**
 * Phase 2: how the tier router decides.
 *
 * <ul>
 *   <li>{@code ALWAYS_TIER_1} / {@code ALWAYS_TIER_2} - experimental baselines; no inference at all.</li>
 *   <li>{@code RULE_ONLY} - deterministic structural signals only; never touches the embedding model.</li>
 *   <li>{@code HYBRID} - structural strong signals first, then local multilingual-e5 semantic scoring.</li>
 * </ul>
 *
 * <p>There is deliberately no automatic fallback between strategies: if HYBRID is configured but the
 * semantic resources cannot initialize, startup fails loudly instead of silently degrading to
 * RULE_ONLY and contaminating experimental results.
 */
public enum RoutingStrategy {
    ALWAYS_TIER_1,
    ALWAYS_TIER_2,
    RULE_ONLY,
    HYBRID
}
