package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;

/**
 * Provider retry policy (Phase 1): retries are explicit per tier and the
 * baseline is zero automatic retries - no hidden SDK retry loops.
 */
class ProviderRetryPolicyTest {

    @Test
    void maxRetriesDefaultsToZero() {
        // One property under the prefix so the binder engages; max-retries is
        // absent and must fall back to the @DefaultValue of 0.
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "voxticket.ai.tiers.tier1.provider", "GROQ")));
        var props = binder.bind("voxticket.ai.tiers.tier1", TierChatProperties.class)
                .orElseThrow(() -> new IllegalStateException("binding failed"));
        assertThat(props.maxRetries()).isZero();
    }

    @Test
    void maxRetriesBindsFromConfiguration() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "voxticket.ai.tiers.tier1.provider", "GROQ",
                "voxticket.ai.tiers.tier1.max-retries", "2")));
        var props = binder.bind("voxticket.ai.tiers.tier1", TierChatProperties.class)
                .orElseThrow(() -> new IllegalStateException("binding failed"));
        assertThat(props.maxRetries()).isEqualTo(2);
    }

    @Test
    void zeroMaxRetriesMakesExactlyOneAttempt() {
        RetryTemplate template = ProviderChatModelFactory.retryTemplateFor(0);
        AtomicInteger attempts = new AtomicInteger();
        // The template wraps the exhausted-policy failure in RetryException -
        // the point is the operation ran exactly once, with no retry.
        assertThatThrownBy(() -> template.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(RetryException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void googleModelDoesNotUseTheFrameworkDefaultRetryTemplate() throws Exception {
        var factory = new ProviderChatModelFactory();
        var model = factory.chatModelFor(
                AiProvider.GOOGLE,
                new ProviderProperties(true, "google-key", null),
                new TierChatProperties(AiProvider.GOOGLE, "gemini-3.6-flash", 0.3, 1024, "none", 20, 0),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        assertThat(model).isInstanceOf(GoogleGenAiChatModel.class);
        Field retryField = GoogleGenAiChatModel.class.getDeclaredField("retryTemplate");
        retryField.setAccessible(true);
        Object retryTemplate = retryField.get(model);
        // The framework default (with its own retry loop) must not be in play -
        // the tier's explicit policy is wired instead.
        assertThat(retryTemplate).isNotSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);
        assertThat(retryTemplate).isInstanceOf(RetryTemplate.class);
    }
}
