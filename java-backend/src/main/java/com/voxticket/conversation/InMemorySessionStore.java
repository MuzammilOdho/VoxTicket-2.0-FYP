package com.voxticket.conversation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Spec §7. Per-session locking (spec: "one session cannot be concurrently
 * mutated by two turns") via a dedicated {@link ReentrantLock} per
 * sessionId. The lock map is never evicted - fine at FYP/single-instance
 * scale; a real deployment would want idle-session/lock eviction, which is
 * exactly the kind of concern a future RedisSessionStore would take over
 * rather than something to build here prematurely.
 *
 * <p>Lock acquisition is bounded ({@link #LOCK_WAIT_MS}): a turn that cannot
 * get the lock within the budget (e.g. a barge-in turn arriving while the
 * aborted previous turn is still unwinding) fails fast with
 * {@link SessionBusyException} -&gt; HTTP 429 - instead of stalling the
 * caller behind the previous turn. The voice worker retries on 429.
 */
@Component
public class InMemorySessionStore implements SessionStore {

    /**
     * Max time a turn waits for its session lock before failing fast with
     * {@link SessionBusyException}. 2s: an aborted voice turn releases its
     * lock within one token generation after the SSE emit fails, so the
     * normal barge-in race lands well inside this budget; waiting longer
     * would stall the caller behind an obsolete turn.
     */
    static final long LOCK_WAIT_MS = 2000;

    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> voiceGenerations = new ConcurrentHashMap<>();

    @Override
    public long nextVoiceGeneration(String sessionId) {
        return voiceGenerations.computeIfAbsent(sessionId, id -> new AtomicLong(0)).incrementAndGet();
    }

    @Override
    public long currentVoiceGeneration(String sessionId) {
        AtomicLong gen = voiceGenerations.get(sessionId);
        return gen == null ? 0 : gen.get();
    }

    @Override
    public <T> T withSession(String sessionId, Channel channel, Function<ConversationSession, T> work) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        long startNanos = System.nanoTime();
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionBusyException(
                    sessionId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), e);
        }
        if (!acquired) {
            throw new SessionBusyException(sessionId, LOCK_WAIT_MS);
        }
        try {
            ConversationSession session = sessions.computeIfAbsent(sessionId, id -> ConversationSession.newSession(id, channel));
            T result = work.apply(session);
            session.touch();
            return result;
        } finally {
            lock.unlock();
        }
    }
}
