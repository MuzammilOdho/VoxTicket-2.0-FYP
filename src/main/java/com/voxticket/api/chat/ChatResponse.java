package com.voxticket.api.chat;

import com.voxticket.conversation.AssistantTurn;

public record ChatResponse(String sessionId, String text, boolean requiresVerification, boolean requiresConfirmation, String identityAssurance, int turnNumber) {

    public static ChatResponse from(AssistantTurn assistantTurn) {
        var state = assistantTurn.conversationState();
        return new ChatResponse(
                state.sessionId(), assistantTurn.text(), assistantTurn.requiresVerification(),
                assistantTurn.requiresConfirmation(), state.identityAssurance().name(), state.turnNumber());
    }
}