package com.voxticket.api.voice;

import jakarta.validation.constraints.NotBlank;

/**
 * One voice turn: the LiveKit worker sends the STT transcript of what the
 * caller said. {@code sessionId} is the LiveKit room name, so turns from one
 * call share one conversation session. No {@code customerPhone} - unlike the
 * dev chat endpoint, the voice path never lets the caller assert an identity.
 */
public record VoiceTurnRequest(@NotBlank(message = "sessionId must not be blank") String sessionId,
                               @NotBlank(message = "message must not be blank") String message) {
}
