package com.voxticket.conversation;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Spec §7. Kept intentionally small - session-scoped turn execution - so a
 * future RedisSessionStore only has to replace how the session is
 * fetched/persisted/owned, not the contract every caller codes against.
 * There is deliberately no lock-free "peek" method: reading a session's
 * mutable collections without ownership would be a real (if narrow,
 * dev-scale) data race, so any read-only need should go through
 * {@link #withSession} too.
 */
public interface SessionStore {
    <T> T withSession(String sessionId, Channel channel, Function<ConversationSession, T> work);

    /**
     * P1.1: generation-aware session execution.
     *
     * <p>{@code voiceGeneration} 0 keeps the old exclusive semantics (bounded
     * wait, never preempts, never preempted) for chat turns. A voice turn
     * (generation &gt; 0) <em>preempts</em> an older voice generation's turn
     * immediately instead of waiting it out; against a newer generation or a
     * chat holder it waits bounded and then fails fast with
     * {@link SessionBusyException} (the 429 path).
     *
     * <p>The turn's {@link TurnHandle} is handed to the work function for
     * ownership checks and interruptible-work registration; it is also
     * installed as the thread's {@link TurnContext} for the duration.
     */
    <T> T withSession(String sessionId, Channel channel, long voiceGeneration,
            BiFunction<ConversationSession, TurnHandle, T> work);

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

    /**
     * P4.2: immutable snapshots of the currently live voice sessions, for
     * the read-only supervisor monitor.
     *
     * <p>This is the one sanctioned exception to the "no lock-free peek"
     * rule: a monitor must never take session ownership (that would block
     * turns), so the implementation builds defensive copies of the fields
     * the monitor needs. Values are point-in-time and weakly consistent -
     * fine for a dashboard, never for business decisions.
     *
     * @return snapshots of live {@link Channel#PHONE} sessions, possibly empty
     */
    default java.util.List<LiveSessionView> liveSessions() {
        return java.util.List.of();
    }
}