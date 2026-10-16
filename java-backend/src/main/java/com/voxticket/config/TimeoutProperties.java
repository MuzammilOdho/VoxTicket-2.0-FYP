package com.voxticket.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * P0.1: bounds every blocking phase of a turn. All values are seconds.
 *
 * <ul>
 *   <li>{@code toolSeconds} - per tool-call timeout. A tool that exceeds it
 *       is interrupted and the model receives the same sanitized
 *       {@code TOOL_EXECUTION_FAILED} envelope as a tool exception, so the
 *       turn continues instead of hanging (and holding the session lock).</li>
 *   <li>{@code ragSeconds} - total budget for one RAG round-trip (Ollama
 *       embedding + pgvector search). On expiry the search is skipped and
 *       the turn continues without policy context; the skip is recorded on
 *       the turn trace ({@code ragSkipped}) so the degradation is visible.</li>
 *   <li>{@code jpaSeconds} - JDBC statement timeout applied via
 *       {@code @Transactional(timeout = ...)} on the voice turn path. The
 *       annotation value is a literal kept in sync with this default (Spring
 *       requires a constant there).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "voxticket.timeouts")
public record TimeoutProperties(
        @DefaultValue("10") int toolSeconds,
        @DefaultValue("8") int ragSeconds,
        @DefaultValue("10") int jpaSeconds) {

    /** Per tool-call timeout as a {@link Duration}. */
    public Duration toolTimeout() {
        return Duration.ofSeconds(toolSeconds);
    }

    /** Total RAG round-trip budget as a {@link Duration}. */
    public Duration ragTimeout() {
        return Duration.ofSeconds(ragSeconds);
    }
}
