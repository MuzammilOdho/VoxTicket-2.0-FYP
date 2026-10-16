package com.voxticket.conversation;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P1.1: the per-turn session-ownership handle.
 *
 * <p>One handle is minted per ownership acquisition (see
 * {@link SessionOwnership#acquire}) and identifies the turn for the whole
 * turn lifecycle. It serves two purposes:
 *
 * <ul>
 *   <li><b>Ownership checks</b> ({@link #isCurrent()}, {@link #checkOwned()}):
 *       a preempted turn detects the loss at phase transitions and aborts
 *       with {@link TurnAbortedException} before starting more work. The
 *       check is identity-based against the ownership record, so it is exact
 *       even when a newer turn already owns the session.</li>
 *   <li><b>Work registration</b> ({@link #registerWork(Future)},
 *       {@link #unregisterWork(Future)}): the turn registers its current
 *       interruptible unit (tool-call future, RAG future) as it moves between
 *       phases; preemption cancels the registered future. Registration
 *       re-checks ownership afterwards to close the check-then-register
 *       race: work registered after preemption is cancelled immediately.</li>
 * </ul>
 */
public final class TurnHandle {

    private final SessionOwnership ownership;
    private final String sessionId;
    private final long voiceGeneration;
    private final AtomicReference<Future<?>> currentWork = new AtomicReference<>();

    TurnHandle(SessionOwnership ownership, String sessionId, long voiceGeneration) {
        this.ownership = ownership;
        this.sessionId = sessionId;
        this.voiceGeneration = voiceGeneration;
    }

    /** This turn's voice generation (0 = chat / untracked). */
    public long voiceGeneration() {
        return voiceGeneration;
    }

    /** True when this handle still owns its session. Never throws. */
    public boolean isCurrent() {
        try {
            return ownership.isOwner(sessionId, this);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Throws {@link TurnAbortedException} unless this handle still owns its
     * session. Call at phase transitions (before provider calls, before tool
     * batches) so a preempted turn stops promptly instead of doing more work.
     */
    public void checkOwned() {
        if (!isCurrent()) {
            throw new TurnAbortedException(
                    new IllegalStateException("turn superseded: lost session ownership"));
        }
    }

    /**
     * Registers the turn's current interruptible work. If ownership was lost
     * between the caller's check and this registration, the future is
     * cancelled immediately so it never runs for a dead turn.
     */
    public void registerWork(Future<?> future) {
        currentWork.set(future);
        if (!isCurrent()) {
            future.cancel(true);
        }
    }

    /** Unregisters a completed future. Only clears when it is still the registered one. */
    public void unregisterWork(Future<?> future) {
        currentWork.compareAndSet(future, null);
    }

    /** Cancels the registered work, if any. Called by preemption. */
    void cancelWork() {
        Future<?> future = currentWork.getAndSet(null);
        if (future != null) {
            future.cancel(true);
        }
    }
}
