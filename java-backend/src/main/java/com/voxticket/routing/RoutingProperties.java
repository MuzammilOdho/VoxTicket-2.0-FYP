package com.voxticket.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2: tier-router configuration, bound from {@code voxticket.ai.selector}.
 *
 * <p>Deliberately separate from {@code voxticket.ai.tiers} (which names providers/models):
 * the router decides ONLY the tier, never the provider or model ID, so no provider or model
 * identifiers may appear anywhere under this prefix.
 *
 * <pre>
 * voxticket:
 *   ai:
 *     selector:
 *       strategy: HYBRID
 *       structural:
 *         long-message-threshold: 300
 *         min-order-references-for-tier2: 2
 *       semantic:
 *         enabled: true
 *         model: intfloat/multilingual-e5-small
 *         model-path: ${ROUTING_MODEL_PATH:}
 *         examples: classpath:routing/examples-v1.json
 *         top-k-per-class: 3
 *         complex-margin: 0.02   # not calibrated (Phase 3): reason label only
 *         simple-margin: 0.01135858377758675   # calibrated in Phase 3 on the validation set
 * </pre>
 */
@ConfigurationProperties(prefix = "voxticket.ai.selector")
public record RoutingProperties(
        @DefaultValue("HYBRID") RoutingStrategy strategy,
        @NestedConfigurationProperty StructuralRoutingProperties structural,
        @NestedConfigurationProperty SemanticRoutingProperties semantic) {

    /**
     * The binder leaves a nested record {@code null} when its whole YAML subtree is absent.
     * Default it to the documented {@code @DefaultValue}s instead - every consumer (notably
     * {@link StructuralFeatureExtractor}, built at context startup) may assume non-null.
     */
    public RoutingProperties {
        if (structural == null) {
            structural = new StructuralRoutingProperties(300, 2);
        }
        if (semantic == null) {
            semantic = new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "",
                    "classpath:routing/examples-v1.json", 3, 0.02, 0.01135858377758675);
        }
    }
}
