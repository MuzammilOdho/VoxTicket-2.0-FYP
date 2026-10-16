package com.voxticket.agent;

/**
 * P1.2: side-effect classification for {@code @Tool} methods.
 *
 * <ul>
 *   <li>{@code READ_ONLY} - the tool only reads (order/ticket lookups,
 *       policy search). Safe to attempt even in racy preemption windows;
 *       still cancelled when its turn is preempted.</li>
 *   <li>{@code MUTATING} - the tool changes procedure state (starting a
 *       cancellation/return/claim, abandoning or controlling a procedure).
 *       Never executes without current session ownership (see
 *       {@link com.voxticket.conversation.TurnHandle#checkOwned()}).</li>
 * </ul>
 *
 * <p>Recorded on the turn trace's tool entries so operators can see which
 * calls were allowed to mutate.
 */
public enum ToolSideEffect {
    READ_ONLY,
    MUTATING
}
