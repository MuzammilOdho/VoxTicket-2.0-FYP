package com.voxticket.routing;

/**
 * Phase 2: stable, enum-backed routing reason codes.
 *
 * <p>These replace the old free-form routing strings ("default", "long_message", ...). The code is
 * what lands in the {@code reason} tag of {@code voxticket.model.selection} and in the
 * {@code event=model_selected} / {@code event=routing_decision} logs, so dashboards and
 * experiments can rely on a closed, stable set.
 *
 * <p>No provider or model information ever belongs in a routing reason - the router decides only
 * the tier.
 */
public enum RoutingReason {
    /** Strategy {@code ALWAYS_TIER_1} forced the tier; no signals or semantics consulted. */
    FORCED_TIER_1,
    /** Strategy {@code ALWAYS_TIER_2} forced the tier; no signals or semantics consulted. */
    FORCED_TIER_2,

    /** Structural: 2+ distinct {@code ORD-*} references in the message. */
    RULE_MULTI_ORDER_REFERENCE,
    /** Structural: message longer than the configured long-message threshold. */
    RULE_LONG_COMPOUND_MESSAGE,
    /** Structural: active + paused procedure combined with a new substantive request. */
    RULE_COMPLEX_SESSION_STATE,
    /** Structural ({@code RULE_ONLY}): no complexity signal fired; the simple default tier. */
    RULE_DEFAULT,

    /** Semantic: relative class margin clearly favored the simple prototypes. */
    SEMANTIC_SIMPLE,
    /** Semantic: relative class margin clearly favored the complex prototypes. */
    SEMANTIC_COMPLEX,
    /** Semantic: margin fell between the placeholder margins; Tier 2 chosen conservatively. */
    SEMANTIC_AMBIGUOUS
}
