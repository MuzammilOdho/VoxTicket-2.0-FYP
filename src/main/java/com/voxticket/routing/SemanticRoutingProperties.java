package com.voxticket.routing;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2: semantic routing knobs, bound from {@code voxticket.ai.selector.semantic}.
 *
 * <p>The margins are TEMPORARY PLACEHOLDER defaults only - they are not scientifically
 * calibrated. Threshold calibration belongs to Phase 3; do not tune them to make examples
 * pass.
 */
public record SemanticRoutingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("intfloat/multilingual-e5-small") String model,
        /** Directory holding {@code model.onnx} + {@code tokenizer.json}; {@code ${ROUTING_MODEL_PATH:}}. */
        @DefaultValue("") String modelPath,
        @DefaultValue("classpath:routing/examples-v1.json") String examples,
        @DefaultValue("3") int topKPerClass,
        /** Temporary placeholder - NOT calibrated (Phase 3). */
        @DefaultValue("0.02") double complexMargin,
        /** Temporary placeholder - NOT calibrated (Phase 3). */
        @DefaultValue("0.02") double simpleMargin) {
}
