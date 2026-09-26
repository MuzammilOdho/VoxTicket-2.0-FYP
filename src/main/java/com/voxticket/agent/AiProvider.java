package com.voxticket.agent;

/**
 * The chat-completion providers VoxTicket can talk to.
 *
 * <p>GOOGLE is served by the native Spring AI Google GenAI integration
 * ({@code GoogleGenAiChatModel} on the official GenAI SDK, API-key mode) -
 * it needs no base URL. GROQ and CEREBRAS expose OpenAI-compatible
 * {@code /chat/completions} APIs and share the {@code OpenAiChatModel} code
 * path, differing only by base URL and API key. Both differences come from
 * configuration, never from Java code - see {@link ProviderChatModelFactory},
 * the one place provider-specific construction lives.
 *
 * <p>OpenAI-compatible base URLs (verified 2026-09-26):
 * <ul>
 *   <li>GROQ - GroqCloud OpenAI-compatible endpoint
 *       ({@code https://api.groq.com/openai/v1})</li>
 *   <li>CEREBRAS - Cerebras OpenAI-compatible endpoint
 *       ({@code https://api.cerebras.ai/v1})</li>
 * </ul>
 */
public enum AiProvider {

    GOOGLE,
    GROQ,
    CEREBRAS;

    /** The environment variable that carries this provider's API key. Keys never live in config files. */
    public String environmentVariable() {
        return switch (this) {
            case GOOGLE -> "GEMINI_API_KEY";
            case GROQ -> "GROQ_API_KEY";
            case CEREBRAS -> "CEREBRAS_API_KEY";
        };
    }

    /** The {@code voxticket.ai.providers.*} config section for this provider. */
    public String configKey() {
        return name().toLowerCase();
    }

    /**
     * Whether the provider needs a configured base URL. GOOGLE is served by
     * the native Google GenAI SDK in API-key mode, which has the Gemini
     * endpoint built in - no base URL is required (or used) for it.
     */
    public boolean requiresBaseUrl() {
        return switch (this) {
            case GOOGLE -> false;
            case GROQ, CEREBRAS -> true;
        };
    }
}
