package com.voxticket.agent;

import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code spring.ai.tools.limits} configuration into VoxTicket's own
 * properties record.
 *
 * <p>Background: {@link TierChatClientRegistry} builds its
 * {@code DefaultToolCallingManager} manually (so the sanitized
 * {@link SanitizedToolExecutionExceptionProcessor} is always the processor in
 * use), which means the framework's own {@code spring.ai.tools.*}
 * auto-configuration never applies on the conversation path. These values are
 * therefore read explicitly and passed to the manager builder - without this
 * record the YAML block would be silently ignored and the framework's
 * 40-calls-per-tool / 150-total defaults would apply instead of the
 * configured 5/10.
 */
@ConfigurationProperties(prefix = "spring.ai.tools.limits")
public record ToolCallLimitsProperties(
        @DefaultValue("5") int maxCallsPerToolDefault,
        @DefaultValue("10") int maxTotalToolCalls,
        @DefaultValue("THROW") ToolCallLimitBehavior onLimitExceeded) {
}
