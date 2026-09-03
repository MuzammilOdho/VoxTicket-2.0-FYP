package com.voxticket.api.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Spec §3.2. {@code sessionId} is optional - if omitted, one is generated and returned in the response so the caller can continue the conversation. */
public record ChatRequest(
        String sessionId,
        @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "customerPhone must be in E.164 format, e.g. +923001234567")
        String customerPhone,
        @NotBlank(message = "message must not be blank")
        String message) {
}