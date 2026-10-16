package com.voxticket.conversation;

/**
 * P1.1: carries the current turn's {@link TurnHandle} on the turn's thread.
 *
 * <p>Tool bodies and phase gates run on the turn's thread (the servlet thread
 * driving the turn), so a {@code ThreadLocal} delivers each turn its own
 * handle with no race: preemption happens on a different thread and can never
 * overwrite this thread's value. Explicit parameter passing would require
 * churning every {@code SupportAgent} and tool signature; the ThreadLocal is
 * set and cleared in exactly one place
 * ({@link ConversationRuntime#processTurnInternal}) inside a
 * {@code try/finally}, so it cannot leak across turns on pooled threads.
 *
 * <p>A null return means "no turn context" (unit tests calling tools
 * directly, or code paths outside a turn): callers must degrade to the
 * pre-P1 behavior (no ownership checks, no work registration).
 */
public final class TurnContext {

    private static final ThreadLocal<TurnHandle> CURRENT = new ThreadLocal<>();

    private TurnContext() {
    }

    /** Installs the turn's handle for the current thread. */
    public static void setCurrent(TurnHandle handle) {
        CURRENT.set(handle);
    }

    /** The current thread's turn handle, or null when outside a turn. */
    public static TurnHandle current() {
        return CURRENT.get();
    }

    /** Clears the current thread's handle. Always call in a {@code finally}. */
    public static void clear() {
        CURRENT.remove();
    }
}
