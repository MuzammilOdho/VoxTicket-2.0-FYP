package com.voxticket.conversation;

import java.time.Instant;

public record ConversationMessage(MessageRole role, String text, int turnNumber, Instant timestamp) {
}