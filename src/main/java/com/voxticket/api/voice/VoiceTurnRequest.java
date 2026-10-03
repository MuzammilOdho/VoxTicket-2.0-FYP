package com.voxticket.api.voice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for the voice turn endpoint. {@code sessionId} is optional - if omitted, one is generated and returned in the done event. */
public record VoiceTurnRequest(
        String sessionId,
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "callerPhone must be in E.164 format, e.g. +923001234567")
        String callerPhone,
        @NotBlank(message = "text must not be blank")
        String text) {
}
