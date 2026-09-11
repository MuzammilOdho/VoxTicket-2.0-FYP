package com.voxticket.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about WHICH chat model gets used and how it behaves per
 * request - model IDs, temperature, and the Groq-specific reasoning
 * suppression flag (spec §61: never expose chain-of-thought). Swapping a
 * model or adjusting its behavior is a config change only - nothing in
 * SupportAgent or ModelSelector needs to change.
 */
@ConfigurationProperties(prefix = "voxticket.ai")
public record ModelTierProperties(
        @DefaultValue("openai/gpt-oss-20b") String tier1Model,
        @DefaultValue("openai/gpt-oss-120b") String tier2Model,
        @DefaultValue("0.3") double temperature,
        @DefaultValue("true") boolean suppressReasoning) {
}