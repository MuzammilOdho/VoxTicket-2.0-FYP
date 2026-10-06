package com.voxticket.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Per-tier chat configuration, bound from {@code voxticket.ai.tiers}.
 * Each tier independently names exactly one provider and one model -
 * there is no primary/fallback pairing and no cross-provider routing.
 */
@ConfigurationProperties(prefix = "voxticket.ai.tiers")
public record AiTiersProperties(
        @NestedConfigurationProperty TierChatProperties tier1,
        @NestedConfigurationProperty TierChatProperties tier2) {

    public TierChatProperties forTier(ModelTier tier) {
        TierChatProperties resolved = switch (tier) {
            case TIER_1 -> tier1();
            case TIER_2 -> tier2();
        };
        if (resolved == null) {
            throw new IllegalStateException(
                    "No configuration found for model tier " + tier
                            + " - add a 'voxticket.ai.tiers." + tier.name().toLowerCase() + "' section to the configuration.");
        }
        return resolved;
    }
}
