package com.voxticket.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "voxticket.safety.prompt-guard")
public record PromptGuardProperties(
        @DefaultValue("heuristic") String provider,
        @DefaultValue("meta-llama/llama-prompt-guard-2-86m") String mlModel,
        @DefaultValue("0.5") double mlThreshold,
        @DefaultValue("10") int mlMaxTokens) {
}