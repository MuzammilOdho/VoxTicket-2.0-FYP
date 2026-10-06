package com.voxticket.routing;

import org.springframework.boot.context.properties.bind.DefaultValue;

/** Phase 2: deterministic structural routing knobs, bound from {@code voxticket.ai.selector.structural}. */
public record StructuralRoutingProperties(
        @DefaultValue("300") int longMessageThreshold,
        @DefaultValue("2") int minOrderReferencesForTier2) {
}
