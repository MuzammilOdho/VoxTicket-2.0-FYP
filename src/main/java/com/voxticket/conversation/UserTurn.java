package com.voxticket.conversation;

import java.time.Instant;
import java.util.Map;

/**
 * Spec §4. Channel-neutral input to {@link ConversationRuntime#processTurn}.
 *
 * <p>{@code callerPhone} extends the spec's suggested field list: it's the
 * phone channel's caller ID and the chat channel's {@code customerPhone} -
 * the same low-assurance identity signal from two different channels, so
 * it's modeled as one channel-neutral field here rather than something each
 * channel adapter has to smuggle through {@code providerMetadata} with a
 * magic key.
 */
public record UserTurn(String sessionId, Channel channel, String text, String callerPhone, Instant timestamp, Map<String, String> providerMetadata) {
}