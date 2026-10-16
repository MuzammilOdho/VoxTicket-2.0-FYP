package com.voxticket.conversation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Spec §7. Per-session turn serialization (spec: "one session cannot be
 * concurrently mutated by two turns") via {@link SessionOwnership}: a
 * generation-preemptive ownership record per sessionId. The ownership map is
 * never evicted - fine at FYP/single-instance scale; a real deployment would
 * want idle-session/lock eviction, which is exactly the kind of concern a
 * future RedisSessionStore would take over rather than something to build
 * here prematurely.
 *
 * <p>Acquisition is bounded ({@link #LOCK_WAIT_MS}): a stale turn that cannot
 * take ownership within the budget (e.g. a retried turn arriving while a
 * newer voice generation owns the session) fails fast with
 * {@link SessionBusyException} -&gt; HTTP 429 - instead of stalling the
 * caller. A newer voice generation never waits: it preempts the older
 * holder immediately (see {@link SessionOwnership}). The voice worker
 * retries on 429.
 */
@Component
public class InMemorySessionStore implements SessionStore {

    /**
     * Max time a turn waits for session ownership before failing fast with
     * {@link SessionBusyException}. 2s: only stale retries and chat holders
     * ever wait this long - a newer voice generation preempts immediately
     * instead of waiting. Waiting longer would stall the caller behind an
     * obsolete turn.
     */
    static final long LOCK_WAIT_MS = 2000;

    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final SessionOwnership ownership = new SessionOwnership();
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
        return withSession(sessionId, channel, 0, (session, handle) -> work.apply(session));
    }

    @Override
    public <T> T withSession(String sessionId, Channel channel, long voiceGeneration,
            BiFunction<ConversationSession, TurnHandle, T> work) {
        SessionOwnership.Lease lease = ownership.acquire(sessionId, voiceGeneration, LOCK_WAIT_MS);
        TurnHandle handle = lease.handle();
        try {
            ConversationSession session = sessions.computeIfAbsent(sessionId, id -> ConversationSession.newSession(id, channel));
            session.setActiveTurnHandle(handle);
            try {
                T result = work.apply(session, handle);
                session.touch();
                return result;
            } finally {
                // Identity-checked: a preempted holder must not clear the new
                // owner's handle.
                session.clearActiveTurnHandle(handle);
            }
        } finally {
            lease.release();
        }
    }

    /**
     * P4.2: snapshot the live voice sessions for the read-only supervisor
     * monitor. Iterates the session map without taking ownership and copies
     * the monitor-relevant fields into immutable {@link LiveSessionView}s;
     * chat sessions are excluded (the monitor is a voice-call view).
     */
    @Override
    public java.util.List<LiveSessionView> liveSessions() {
        java.util.List<LiveSessionView> views = new java.util.ArrayList<>();
        for (ConversationSession session : sessions.values()) {
            if (session.getChannel() != Channel.PHONE) {
                continue;
            }
            DialogueState dialogueState = session.getDialogueState();
            java.util.Map<String, Double> confidences = new java.util.LinkedHashMap<>();
            for (Slot slot : dialogueState.getSlots()) {
                confidences.put(slot.name(), slot.confidence());
            }
            InterruptionSignal signal = dialogueState.getLastInterruptionSignal();
            views.add(new LiveSessionView(
                    session.getSessionId(),
                    session.getChannel().name(),
                    session.getTurnCount(),
                    session.getActiveProcedure().map(p -> p.getType().name()).orElse(null),
                    session.getActiveProcedure().map(p -> p.getStatus().name()).orElse(null),
                    dialogueState.pendingRequestCount(),
                    signal == null ? null : signal.interruptionClass().name(),
                    java.util.Map.copyOf(confidences)));
        }
        return java.util.List.copyOf(views);
    }
}
