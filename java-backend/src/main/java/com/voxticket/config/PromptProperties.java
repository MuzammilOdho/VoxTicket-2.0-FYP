package com.voxticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * P3.1: selects the versioned system-prompt template.
 *
 * <p>{@code version} names the template file under
 * {@code src/main/resources/prompts/} ({@code system-{version}.txt},
 * e.g. "v1" -&gt; system-v1.txt).
 * Bumping the version (and adding the new file) is how prompts are changed
 * in production; the old file stays so any version can be re-selected and
 * every turn trace pins exactly what ran.
 */
@ConfigurationProperties(prefix = "voxticket.prompt")
public record PromptProperties(
        @DefaultValue("v1") String version) {
}
