package com.voxticket.agent;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection details for one chat provider, bound from
 * {@code voxticket.ai.providers.<name>}. The API key must come from the
 * provider's environment variable (see {@link AiProvider#environmentVariable()})
 * - it is never committed to a config file.
 *
 * <p>{@code baseUrl} may be null or empty for providers that do not need one
 * (see {@link AiProvider#requiresBaseUrl()}) - GOOGLE uses the native GenAI
 * SDK in API-key mode, which has the endpoint built in.
 */
public record ProviderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        String baseUrl) {
}
