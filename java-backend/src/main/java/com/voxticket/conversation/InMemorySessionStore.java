package com.voxticket.conversation;

import java.util.concurrent.ConcurrentHashMap;
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
 */
@Component
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withSession(String sessionId, Channel channel, Function<ConversationSession, T> work) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        lock.lock();
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