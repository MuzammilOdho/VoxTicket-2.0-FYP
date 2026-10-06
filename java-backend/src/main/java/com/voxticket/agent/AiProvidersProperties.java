package com.voxticket.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * All chat providers VoxTicket knows about, bound from
 * {@code voxticket.ai.providers}. A provider section may be left without an
 * API key (or disabled) as long as no model tier actually references it -
 * {@link TierChatClientRegistry} validates only the providers tiers use, and
 * fails fast with a clear message when one of those is unusable.
 */
@ConfigurationProperties(prefix = "voxticket.ai.providers")
public record AiProvidersProperties(
        @NestedConfigurationProperty ProviderProperties google,
        @NestedConfigurationProperty ProviderProperties groq,
        @NestedConfigurationProperty ProviderProperties cerebras) {

    public ProviderProperties forProvider(AiProvider provider) {
        ProviderProperties resolved = switch (provider) {
            case GOOGLE -> google();
            case GROQ -> groq();
            case CEREBRAS -> cerebras();
        };
        if (resolved == null) {
            throw new IllegalStateException(
                    "No configuration found for provider " + provider
                            + " - add a 'voxticket.ai.providers." + provider.configKey() + "' section to the configuration.");
        }
        return resolved;
    }
}
