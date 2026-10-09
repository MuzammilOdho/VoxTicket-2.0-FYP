package com.voxticket.conversation;

import java.util.function.Function;

/**
 * Spec §7. Kept intentionally small - a single "do work under this
 * session's lock" method - so a future RedisSessionStore only has to
 * replace how the session is fetched/persisted/locked, not the contract
 * every caller codes against. There is deliberately no lock-free "peek"
 * method: reading a session's mutable collections without the lock would
 * be a real (if narrow, dev-scale) data race, so any read-only need should
 * go through {@link #withSession} too.
 */
public interface SessionStore {
    <T> T withSession(String sessionId, Channel channel, Function<ConversationSession, T> work);

    /**
     * Voice barge-in generation counter. Each new voice turn for a session
     * increments and returns the generation (lock-free); the turn records
     * its generation and aborts cooperatively if a newer generation appears
     * (see {@link TurnAbortedException}). This lets a superseded turn
     * release its session lock within one token instead of running to
     * completion, so the barge-in turn usually acquires the lock inside the
     * {@link InMemorySessionStore#LOCK_WAIT_MS} budget instead of 429ing.
     *
     * <p>Voice-only: chat turns never touch generations.
     */
    default long nextVoiceGeneration(String sessionId) {
        return 0;
    }

    /**
     * Current voice generation for a session, or 0 if no voice turn has
     * ever started one. Lock-free read.
     */
    default long currentVoiceGeneration(String sessionId) {
        return 0;
    }
}