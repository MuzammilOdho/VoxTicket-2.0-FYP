package com.voxticket.conversation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1.1: generation-preemptive session ownership.
 *
 * <p>Replaces the old whole-turn {@code ReentrantLock}. Each session has one
 * ownership record holding the current owner's {@link TurnHandle} (or null
 * when unowned). A voice turn carries the generation minted by
 * {@link SessionStore#nextVoiceGeneration}; acquisition behaves as follows:
 *
 * <ul>
 *   <li><b>Unowned</b> - the turn takes ownership immediately.</li>
 *   <li><b>Owned by an older voice generation</b> - the challenger
 *       <em>preempts immediately</em>: the previous owner's registered
 *       interruptible work is cancelled, ownership transfers, and the
 *       challenger proceeds without waiting. The preempted holder aborts at
 *       its next generation/ownership check without committing, speaking, or
 *       writing history.</li>
 *   <li><b>Owned by a newer voice generation (stale retry), or by a chat
 *       turn (generation 0)</b> - chat turns never preempt and are never
 *       preempted, so the challenger waits bounded (the old 2s budget) and
 *       then fails fast with {@link SessionBusyException}, preserving the
 *       429 path for exactly this case.</li>
 * </ul>
 *
 * <p>Releasing is identity-checked: only the owning lease releases, so a
 * preempted holder's {@code finally} can never release the new owner's
 * session. Acquisition and release are idempotent per lease.
 *
 * <p>Threading: the record map is concurrent; each record is guarded by
 * synchronizing on the record itself. Blocking phases of a turn run on
 * interruptible futures (see {@link TurnHandle}), never while holding a
 * record monitor, so preemption cannot deadlock against the holder.
 */
public final class SessionOwnership {

    /** Per-session ownership record. Guarded by synchronizing on the record itself. */
    private static final class Record {
        /** The handle that currently owns the session, or null when unowned. */
        TurnHandle owner;
    }

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();

    /**
     * Acquires ownership of the session for the given voice generation.
     *
     * @param sessionId the session to own
     * @param generation the turn's voice generation (0 = chat: exclusive,
     *        never preempts, never preempted)
     * @param timeoutMs bounded wait for the stale-retry / chat-holder cases
     * @return a lease holding the turn's {@link TurnHandle}; release it in a
     *         {@code finally}
     * @throws SessionBusyException if the budget expires before ownership is
     *         acquired (stale retry only - preemption never waits)
     */
    public Lease acquire(String sessionId, long generation, long timeoutMs) {
        Record record = records.computeIfAbsent(sessionId, id -> new Record());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (record) {
            for (;;) {
                if (record.owner == null) {
                    return takeOwnership(record, sessionId, generation);
                }
                long ownerGeneration = record.owner.voiceGeneration();
                if (generation > 0 && ownerGeneration > 0 && generation > ownerGeneration) {
                    // P1.1 preemption: cancel whatever interruptible work the
                    // previous owner registered, then take the session
                    // immediately. The provider-stream phase has no registered
                    // future (it is bounded by its own 20s timeout instead);
                    // the preempted holder still aborts at its next
                    // generation/ownership check without committing.
                    record.owner.cancelWork();
                    Lease lease = takeOwnership(record, sessionId, generation);
                    record.notifyAll();
                    return lease;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new SessionBusyException(sessionId, timeoutMs);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(record, remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SessionBusyException(
                            sessionId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), e);
                }
            }
        }
    }

    /**
     * True when the given handle is still the session's owner. Used by
     * {@link TurnHandle#isCurrent()} for the per-phase ownership checks.
     */
    boolean isOwner(String sessionId, TurnHandle handle) {
        Record record = records.get(sessionId);
        if (record == null) {
            return false;
        }
        synchronized (record) {
            return record.owner == handle;
        }
    }

    /**
     * Releases ownership, but only when the given handle still owns the
     * session - a preempted holder's release is a silent no-op.
     */
    void release(String sessionId, TurnHandle handle) {
        Record record = records.get(sessionId);
        if (record == null) {
            return;
        }
        synchronized (record) {
            if (record.owner == handle) {
                record.owner = null;
                record.notifyAll();
            }
        }
    }

    private Lease takeOwnership(Record record, String sessionId, long generation) {
        TurnHandle handle = new TurnHandle(this, sessionId, generation);
        record.owner = handle;
        return new Lease(sessionId, handle);
    }

    /**
     * An acquired ownership grant. {@link #release()} is idempotent; always
     * call it in a {@code finally}.
     */
    public final class Lease {
        private final String sessionId;
        private final TurnHandle handle;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(String sessionId, TurnHandle handle) {
            this.sessionId = sessionId;
            this.handle = handle;
        }

        /** The turn's ownership handle, for phase checks and work registration. */
        public TurnHandle handle() {
            return handle;
        }

        /** Releases ownership if this lease still owns the session. Idempotent. */
        public void release() {
            if (released.compareAndSet(false, true)) {
                SessionOwnership.this.release(sessionId, handle);
            }
        }
    }
}
