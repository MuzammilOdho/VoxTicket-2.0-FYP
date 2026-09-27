package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.observability.TurnMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;

/**
 * Regression test for the tool-call limit wiring.
 *
 * <p>{@link TierChatClientRegistry} builds its {@code DefaultToolCallingManager}
 * manually (so the sanitized exception processor is always in use), which
 * means the framework's own {@code spring.ai.tools} auto-configuration never
 * applies on the conversation path. These tests prove the configured
 * {@code spring.ai.tools.limits} values are the effective limits on the
 * registry's manager - a wiring regression would silently restore the
 * framework's 40-per-tool / 150-total defaults and the configured runaway
 * protection would be fiction.
 *
 * <p>The manager exposes no public accessor for its limits, so the effective
 * values are read reflectively (the same technique used to confirm the bug).
 * If the framework renames the field this test fails loudly, which is the
 * correct behavior for a wiring guard.
 */
class ToolCallLimitsEnforcementTest {

    private static TierChatClientRegistry registryWithLimits(int maxCallsPerTool, int maxTotalToolCalls) {
        var providers = new AiProvidersProperties(
                new ProviderProperties(true, "google-key", null),
                new ProviderProperties(true, "groq-key", "https://api.groq.com/openai/v1"),
                new ProviderProperties(true, "cerebras-key", "https://api.cerebras.ai/v1"));
        var tiers = new AiTiersProperties(
                new TierChatProperties(AiProvider.GROQ, "openai/gpt-oss-20b", 0.3, 1024, "none", 20),
                new TierChatProperties(AiProvider.CEREBRAS, "gpt-oss-120b", 0.3, 1024, "none", 20));
        return new TierChatClientRegistry(
                providers, tiers, new ProviderChatModelFactory(), ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                new SanitizedToolExecutionExceptionProcessor(new TurnMetrics(new SimpleMeterRegistry())),
                new ToolCallLimitsProperties(maxCallsPerTool, maxTotalToolCalls, ToolCallLimitBehavior.THROW));
    }

    /** Reads the effective limits off the registry's shared tool-calling advisor manager. */
    private static int[] effectiveLimits(TierChatClientRegistry registry) throws Exception {
        Object limits = toolCallLimits(registry);
        return new int[] {
                (int) field(limits, "defaultMaxCallsPerTool"),
                (int) field(limits, "maxTotalToolCalls"),
        };
    }

    private static String limitBehavior(TierChatClientRegistry registry) throws Exception {
        Object limits = toolCallLimits(registry);
        Method method = limits.getClass().getDeclaredMethod("onLimitExceeded");
        method.setAccessible(true);
        return String.valueOf(method.invoke(limits));
    }

    private static Object toolCallLimits(TierChatClientRegistry registry) throws Exception {
        Field advisorManager = registry.toolCallingAdvisor().getClass().getDeclaredField("toolCallingManager");
        advisorManager.setAccessible(true);
        Object manager = advisorManager.get(registry.toolCallingAdvisor());
        Field limitsField = manager.getClass().getDeclaredField("toolCallLimits");
        limitsField.setAccessible(true);
        return limitsField.get(manager);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void configuredLimitsAreEffective() throws Exception {
        // Matches application.yaml: 5 per tool / 10 total / THROW.
        var registry = registryWithLimits(5, 10);

        assertThat(effectiveLimits(registry)).containsExactly(5, 10);
        assertThat(limitBehavior(registry)).isEqualTo("THROW");
    }

    @Test
    void limitValuesAreParametricNotHardcoded() throws Exception {
        var registry = registryWithLimits(2, 7);

        assertThat(effectiveLimits(registry)).containsExactly(2, 7);
    }
}
