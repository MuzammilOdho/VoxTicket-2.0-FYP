package com.voxticket.safety;

/** Shared structured tool-failure shape, used by every AI-facing tool class so the model gets a consistent error contract. */
public record ToolError(String code, String message) {
}