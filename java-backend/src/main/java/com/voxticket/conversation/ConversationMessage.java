package com.voxticket.conversation;

import java.time.Instant;

/**
 * One message in the session's recent history.
 *
 * @param partial P1.3 (history repair): true when this assistant message is
 *        a repaired partial - exactly the deltas the caller heard before the
 *        turn aborted. Stored metadata only: never spoken, and the admin
 *        transcript may surface it as a visual marker. The next turn's prompt
 *        sees the faithful partial transcript.
 */
public record ConversationMessage(MessageRole role, String text, int turnNumber, Instant timestamp, boolean partial) {

    /** Convenience constructor for complete (non-partial) messages. */
    public ConversationMessage(MessageRole role, String text, int turnNumber, Instant timestamp) {
        this(role, text, turnNumber, timestamp, false);
    }
}
