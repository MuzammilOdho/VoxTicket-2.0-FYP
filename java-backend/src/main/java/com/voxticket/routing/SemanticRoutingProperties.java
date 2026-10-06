package com.voxticket.routing;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2: semantic routing knobs, bound from {@code voxticket.ai.selector.semantic}.
 *
 * <p>{@code simpleMargin} was calibrated in Phase 3 on the 120-example held-out
 * validation set (objective: maximize balanced accuracy; tie-breaks: lower
 * under-routing, higher macro-F1, lower TIER_2 selection rate). {@code complexMargin}
 * is intentionally NOT calibrated: it only renames the TIER_2 reason
 * ({@code SEMANTIC_COMPLEX} vs {@code SEMANTIC_AMBIGUOUS}) and is not identifiable
 * from binary tier labels.
 */
public record SemanticRoutingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("intfloat/multilingual-e5-small") String model,
        /** Directory holding {@code model.onnx} + {@code tokenizer.json}; {@code ${ROUTING_MODEL_PATH:}}. */
        @DefaultValue("") String modelPath,
        @DefaultValue("classpath:routing/examples-v1.json") String examples,
        @DefaultValue("3") int topKPerClass,
        /**
         * Not calibrated (Phase 3 finding): only selects the TIER_2 reason label,
         * never the tier itself. Kept at the Phase 2 value.
         */
        @DefaultValue("0.02") double complexMargin,
        /** Calibrated in Phase 3 (was the 0.02 placeholder). */
        @DefaultValue("0.01135858377758675") double simpleMargin) {
}
