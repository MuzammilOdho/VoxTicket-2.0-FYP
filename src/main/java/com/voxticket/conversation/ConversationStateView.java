package com.voxticket.conversation;

import com.voxticket.identity.IdentityAssurance;

public record ConversationStateView(String sessionId, Channel channel, IdentityAssurance identityAssurance, int turnNumber) {
}