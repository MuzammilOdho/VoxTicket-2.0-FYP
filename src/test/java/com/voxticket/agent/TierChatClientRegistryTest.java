package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

/**
 * Phase 1: each tier independently resolves exactly one provider and model
 * from configuration; the registry builds a working ChatClient per tier and
 * fails fast with a clear message when a tier's provider is unusable.
 * Constructing the clients performs no network I/O, so these run anywhere.
 */
class TierChatClientRegistryTest {

    private static ProviderProperties provider(boolean enabled, String apiKey, String baseUrl) {
        return new ProviderProperties(enabled, apiKey, baseUrl);
    }

    private static AiProvidersProperties providers(ProviderProperties google, ProviderProperties groq, ProviderProperties cerebras) {
        return new AiProvidersProperties(google, groq, cerebras);
    }

    private static AiProvidersProperties allEnabledWithKeys() {
        return providers(
                provider(true, "google-key", "https://generativelanguage.googleapis.com/v1beta/openai"),
                provider(true, "groq-key", "https://api.groq.com/openai/v1"),
                provider(true, "cerebras-key", "https://api.cerebras.ai/v1"));
    }

    private static TierChatProperties tier(AiProvider provider, String model) {
        return new TierChatProperties(provider, model, 0.3, 1024, "none", 20);
    }

    private static AiTiersProperties tiers(TierChatProperties tier1, TierChatProperties tier2) {
        return new AiTiersProperties(tier1, tier2);
    }

    @Test
    void tier1AndTier2ResolveDifferentProviderModelCombinations() {
        var registry = new TierChatClientRegistry(
                allEnabledWithKeys(),
                tiers(tier(AiProvider.GROQ, "openai/gpt-oss-20b"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(registry.resolutionFor(ModelTier.TIER_1))
                .isEqualTo(new TierChatClientRegistry.TierResolution(AiProvider.GROQ, "openai/gpt-oss-20b"));
        assertThat(registry.resolutionFor(ModelTier.TIER_2))
                .isEqualTo(new TierChatClientRegistry.TierResolution(AiProvider.CEREBRAS, "gpt-oss-120b"));

        assertThat(registry.clientFor(ModelTier.TIER_1)).isNotNull();
        assertThat(registry.clientFor(ModelTier.TIER_2)).isNotNull();
        assertThat(registry.clientFor(ModelTier.TIER_1)).isNotSameAs(registry.clientFor(ModelTier.TIER_2));
    }

    @Test
    void manualProviderSwitchRequiresNoJavaChange() {
        // Switching tier1 from GROQ to GOOGLE is a configuration-only change:
        // the same registry code resolves the new provider from properties.
        var registry = new TierChatClientRegistry(
                allEnabledWithKeys(),
                tiers(tier(AiProvider.GOOGLE, "gemini-3.8-flash"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(registry.resolutionFor(ModelTier.TIER_1).provider()).isEqualTo(AiProvider.GOOGLE);
        assertThat(registry.resolutionFor(ModelTier.TIER_1).model()).isEqualTo("gemini-3.8-flash");
        assertThat(registry.clientFor(ModelTier.TIER_1)).isNotNull();
    }

    @Test
    void disabledProviderReferencedByATierFailsClearly() {
        var providers = providers(
                provider(true, "google-key", "https://generativelanguage.googleapis.com/v1beta/openai"),
                provider(false, "groq-key", "https://api.groq.com/openai/v1"),
                provider(true, "cerebras-key", "https://api.cerebras.ai/v1"));

        assertThatThrownBy(() -> new TierChatClientRegistry(
                providers,
                tiers(tier(AiProvider.GROQ, "openai/gpt-oss-20b"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIER_1")
                .hasMessageContaining("GROQ")
                .hasMessageContaining("voxticket.ai.providers.groq.enabled");
    }

    @Test
    void missingApiKeyForATierProviderFailsClearlyNamingTheEnvVar() {
        var providers = providers(
                provider(true, "google-key", "https://generativelanguage.googleapis.com/v1beta/openai"),
                provider(true, "", "https://api.groq.com/openai/v1"),
                provider(true, "cerebras-key", "https://api.cerebras.ai/v1"));

        assertThatThrownBy(() -> new TierChatClientRegistry(
                providers,
                tiers(tier(AiProvider.GROQ, "openai/gpt-oss-20b"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIER_1")
                .hasMessageContaining("GROQ")
                .hasMessageContaining("GROQ_API_KEY");
    }

    @Test
    void missingBaseUrlForATierProviderFailsClearly() {
        var providers = providers(
                provider(true, "google-key", "https://generativelanguage.googleapis.com/v1beta/openai"),
                provider(true, "groq-key", "https://api.groq.com/openai/v1"),
                provider(true, "cerebras-key", ""));

        assertThatThrownBy(() -> new TierChatClientRegistry(
                providers,
                tiers(tier(AiProvider.GROQ, "openai/gpt-oss-20b"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIER_2")
                .hasMessageContaining("CEREBRAS")
                .hasMessageContaining("voxticket.ai.providers.cerebras.base-url");
    }

    @Test
    void unreferencedProviderWithoutApiKeyDoesNotFail() {
        var providers = providers(
                provider(true, "", "https://generativelanguage.googleapis.com/v1beta/openai"),
                provider(true, "groq-key", "https://api.groq.com/openai/v1"),
                provider(true, "cerebras-key", "https://api.cerebras.ai/v1"));

        var registry = new TierChatClientRegistry(
                providers,
                tiers(tier(AiProvider.GROQ, "openai/gpt-oss-20b"), tier(AiProvider.CEREBRAS, "gpt-oss-120b")),
                new ChatOptionsFactory(), ObservationRegistry.NOOP, new SimpleMeterRegistry());

        assertThat(registry.clientFor(ModelTier.TIER_1)).isNotNull();
        assertThat(registry.clientFor(ModelTier.TIER_2)).isNotNull();
    }

    @Test
    void eachProviderNamesItsOwnEnvironmentVariable() {
        assertThat(AiProvider.GOOGLE.environmentVariable()).isEqualTo("GEMINI_API_KEY");
        assertThat(AiProvider.GROQ.environmentVariable()).isEqualTo("GROQ_API_KEY");
        assertThat(AiProvider.CEREBRAS.environmentVariable()).isEqualTo("CEREBRAS_API_KEY");
    }
}
