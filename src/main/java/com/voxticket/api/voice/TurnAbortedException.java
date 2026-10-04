package com.voxticket.api.voice;

/**
 * Unchecked: the voice client disconnected mid-turn (barge-in / hang-up).
 *
 * <p>Thrown from the SSE delta sink inside {@code VoiceTurnController}. It
 * unwinds through {@code ConversationRuntime} - skipping assistant-message
 * recording and audit for the aborted turn - and out through the
 * per-session lock's {@code finally}, so the lock releases within one token
 * generation instead of after a full LLM completion.
 */
public class TurnAbortedException extends RuntimeException {

    public TurnAbortedException(Throwable cause) {
        super("voice turn aborted: client disconnected", cause);
    }
}
