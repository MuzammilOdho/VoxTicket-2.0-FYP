package com.voxticket.conversation;

import java.util.Map;

/** Spec §4. Voice transforms {@code text()} into speech; chat returns it as JSON (via ChatResponse). */
public record AssistantTurn(
        String text, boolean requiresVerification, boolean requiresConfirmation, ConversationStateView conversationState, Map<String, String> metadata) {
}