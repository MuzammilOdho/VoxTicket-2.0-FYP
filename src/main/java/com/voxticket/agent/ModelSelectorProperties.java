package com.voxticket.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tuning knobs for ModelSelector's deterministic complexity signals (spec §53) - kept separate from model IDs so routing logic can be tuned independently. */
@ConfigurationProperties(prefix = "voxticket.ai.selector")
public record ModelSelectorProperties(
        @DefaultValue("300") int longMessageThreshold,
        @DefaultValue("2") int minOrderReferencesForTier2) {
}