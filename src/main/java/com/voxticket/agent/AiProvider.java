package com.voxticket.agent;

/**
 * The chat-completion providers VoxTicket can talk to. Every provider here
 * exposes an OpenAI-compatible {@code /chat/completions} API, so a single
 * Spring AI {@code OpenAiChatModel} code path serves all of them - the only
 * per-provider differences are base URL and API key, and both come from
 * configuration, never from Java code.
 *
 * <p>Base URLs (verified 2026-09-26):
 * <ul>
 *   <li>GOOGLE - Google's OpenAI-compatible Gemini endpoint
 *       ({@code https://generativelanguage.googleapis.com/v1beta/openai})</li>
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
}
