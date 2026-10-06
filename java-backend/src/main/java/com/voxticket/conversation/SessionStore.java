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
}