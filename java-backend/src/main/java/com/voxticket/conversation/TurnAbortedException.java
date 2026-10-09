package com.voxticket.conversation;

/**
 * Unchecked: the voice client disconnected mid-turn (barge-in / hang-up).
 *
 * <p>Moved from {@code com.voxticket.api.voice} in P0: the abort is a
 * conversation-lifecycle signal, not a web-layer concern.
 * {@code ConversationRuntime} catches it to classify the turn as
 * {@code aborted} (audit marker + metric) before rethrowing; it unwinds
 * through the per-session lock's {@code finally}, so the lock releases
 * within one token generation instead of after a full LLM completion.
 *
 * <p>Thrown from the SSE delta sink inside {@code VoiceTurnController}. It
 * unwinds through {@code ConversationRuntime} - skipping assistant-message
 * recording and audit for the aborted turn - and out through the
 * per-session lock.
 */
public class TurnAbortedException extends RuntimeException {

    public TurnAbortedException(Throwable cause) {
        super("voice turn aborted: client disconnected", cause);
    }
}
