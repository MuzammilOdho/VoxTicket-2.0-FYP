package com.voxticket.api.voice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One voice turn: the LiveKit worker sends the STT transcript of what the
 * caller said. {@code sessionId} is the LiveKit room name, so turns from one
 * call share one conversation session.
 *
 * <p>{@code customerPhone} is the caller's identity as minted by this
 * backend's own {@code /api/v1/voice/token} endpoint: the demo frontend
 * passes the backend-assigned customer's E.164 phone as the LiveKit
 * participant identity, and the worker reads it back from the room and
 * forwards it here. The caller never types or asserts it - like the chat
 * endpoint's phone, it is only as trustworthy as the token minter, which is
 * why this whole surface stays dev/test-only. Null means an anonymous turn
 * (e.g. a non-demo caller); the runtime then behaves exactly as before.
 */
public record VoiceTurnRequest(@NotBlank(message = "sessionId must not be blank") String sessionId,
                               @NotBlank(message = "message must not be blank") String message,
                               @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "customerPhone must be in E.164 format, e.g. +923001234567")
                               String customerPhone) {

    /** Anonymous turn: no caller identity carried (the pre-identity-sync shape). */
    public VoiceTurnRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }
}
