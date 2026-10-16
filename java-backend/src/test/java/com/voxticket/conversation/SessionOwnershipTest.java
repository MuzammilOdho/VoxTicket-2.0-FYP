package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.agent.ToolCallTimeout;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * P1.1: generation-preemptive session ownership.
 */
class SessionOwnershipTest {

    private final SessionOwnership ownership = new SessionOwnership();

    @Test
    void firstAcquisitionTakesOwnershipImmediately() {
        SessionOwnership.Lease lease = ownership.acquire("s1", 1, 2000);

        assertThat(lease.handle().voiceGeneration()).isEqualTo(1);
        assertThat(lease.handle().isCurrent()).isTrue();

        lease.release();
    }

    @Test
    void newerVoiceGenerationPreemptsOlderImmediately() {
        SessionOwnership.Lease older = ownership.acquire("s2", 1, 2000);

        long start = System.nanoTime();
        SessionOwnership.Lease newer = ownership.acquire("s2", 2, 2000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // No 2s budget consumed: preemption does not wait.
        assertThat(elapsedMs).isLessThan(2000);
        assertThat(newer.handle().voiceGeneration()).isEqualTo(2);
        assertThat(newer.handle().isCurrent()).isTrue();
        assertThat(older.handle().isCurrent()).isFalse();

        // The preempted holder's release is a no-op: it must not disturb the
        // new owner.
        older.release();
        assertThat(newer.handle().isCurrent()).isTrue();

        newer.release();
        assertThat(newer.handle().isCurrent()).isFalse();
    }

    @Test
    void preemptionCancelsTheRegisteredInterruptibleWork() throws Exception {
        SessionOwnership.Lease older = ownership.acquire("s3", 1, 2000);
        FutureTask<Object> toolFuture = new FutureTask<>(() -> {
            Thread.sleep(30_000);
            return null;
        });
        new Thread(toolFuture).start();
        older.handle().registerWork(toolFuture);

        ownership.acquire("s3", 2, 2000);

        assertThat(toolFuture.isCancelled()).isTrue();
    }

    @Test
    void workRegisteredAfterPreemptionIsCancelledImmediately() {
        SessionOwnership.Lease older = ownership.acquire("s4", 1, 2000);
        ownership.acquire("s4", 2, 2000);

        // Closes the check-then-register race: registration re-checks
        // ownership, so work registered after the loss never runs for a dead
        // turn.
        FutureTask<Object> late = new FutureTask<>(() -> null);
        older.handle().registerWork(late);

        assertThat(late.isCancelled()).isTrue();
    }

    @Test
    void preemptedHandleFailsTheOwnershipCheck() {
        SessionOwnership.Lease older = ownership.acquire("s5", 1, 2000);
        ownership.acquire("s5", 2, 2000);

        assertThatThrownBy(() -> older.handle().checkOwned())
                .isInstanceOf(TurnAbortedException.class);
    }

    @Test
    void staleVoiceGenerationWaitsThenFailsFast() {
        SessionOwnership.Lease newer = ownership.acquire("s6", 5, 2000);

        // A stale retry (older generation) never preempts: it waits bounded
        // and then fails fast with 429, like the old lock.
        assertThatThrownBy(() -> ownership.acquire("s6", 3, 300))
                .isInstanceOf(SessionBusyException.class);

        newer.release();
    }

    @Test
    void chatGenerationNeverPreemptsAndIsNeverPreempted() {
        // A chat turn (generation 0) holds the session; a voice challenger
        // must wait instead of preempting.
        SessionOwnership.Lease chat = ownership.acquire("s7", 0, 2000);
        assertThatThrownBy(() -> ownership.acquire("s7", 7, 300))
                .isInstanceOf(SessionBusyException.class);
        chat.release();

        // And a chat turn never preempts a voice holder either: it waits and
        // fails fast.
        SessionOwnership.Lease voice = ownership.acquire("s7", 7, 2000);
        assertThatThrownBy(() -> ownership.acquire("s7", 0, 300))
                .isInstanceOf(SessionBusyException.class);
        voice.release();
    }

    @Test
    void releaseIsIdempotent() {
        SessionOwnership.Lease lease = ownership.acquire("s8", 1, 2000);
        lease.release();
        lease.release();

        // The session is free again for a fresh turn.
        SessionOwnership.Lease again = ownership.acquire("s8", 2, 2000);
        assertThat(again.handle().isCurrent()).isTrue();
        again.release();
    }

    @Test
    void normalReleaseWakesAWaitingStaleTurn() throws Exception {
        SessionOwnership.Lease holder = ownership.acquire("s9", 5, 2000);
        AtomicReference<SessionOwnership.Lease> waiterLease = new AtomicReference<>();
        Thread waiter = new Thread(() ->
                waiterLease.set(ownership.acquire("s9", 5, 2000)));
        waiter.start();

        Thread.sleep(200);
        holder.release();
        waiter.join(2000);

        assertThat(waiterLease.get()).isNotNull();
        assertThat(waiterLease.get().handle().isCurrent()).isTrue();
        waiterLease.get().release();
    }

    @Test
    void challengerPreemptsPromptlyWhileHolderIsInASlowTool() throws Exception {
        SessionOwnership.Lease olderLease = ownership.acquire("s10", 1, 2000);
        TurnHandle olderHandle = olderLease.handle();
        CountDownLatch toolStarted = new CountDownLatch(1);
        AtomicReference<Object> holderOutcome = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            // Simulates the turn thread: the runtime installs the handle as
            // the thread's turn context at turn start.
            TurnContext.setCurrent(olderHandle);
            try {
                holderOutcome.set(ToolCallTimeout.call("slowTool", Duration.ofSeconds(30), () -> {
                    toolStarted.countDown();
                    Thread.sleep(30_000);
                    return "done";
                }));
            } catch (TurnAbortedException aborted) {
                // The runtime's abort catch turns this into an
                // abort-without-commit; the tool result is discarded.
                holderOutcome.set(aborted);
            } finally {
                TurnContext.clear();
                olderLease.release();
            }
        });
        holder.start();

        assertThat(toolStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        SessionOwnership.Lease challenger = ownership.acquire("s10", 2, 2000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(challenger.handle().isCurrent()).isTrue();

        holder.join(5000);
        assertThat(holderOutcome.get()).isInstanceOf(TurnAbortedException.class);

        challenger.release();
    }
}
