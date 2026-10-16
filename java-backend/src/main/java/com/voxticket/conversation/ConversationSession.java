package com.voxticket.conversation;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.observability.TurnTrace;
import com.voxticket.procedure.DeferredProcedureIntent;
import com.voxticket.procedure.ProcedureState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory conversation state driving the live conversation.
 *
 * <p>P0 (session lifecycle). Lifecycle is <em>derived at read time</em> from
 * the audit record - never written as state:
 * <ul>
 *   <li>ACTIVE: {@code lastActivityAt} within
 *       {@code voxticket.admin.active-session-window-minutes} (default 15).</li>
 *   <li>ABORTED: the last recorded turn outcome is {@code "aborted"}
 *       (voice client disconnected mid-turn).</li>
 *   <li>COMPLETED: a terminal turn outcome was recorded and the session is
 *       idle beyond the active window.</li>
 * </ul>
 * The async audit writer upserts {@code turn_count} /
 * {@code last_turn_outcome} onto {@code conversation_sessions} at every
 * turn completion; admin queries apply the definitions above.
 */
public class ConversationSession {

    private static final int MAX_RECENT_ACTIONS = 10;
    private static final int MAX_RECENT_MESSAGES = 40;

    private final String sessionId;
    private final Channel channel;
    private CustomerIdentity customerIdentity;
    private final Deque<RecentAction> recentActions = new ArrayDeque<>();
    private final List<ConversationMessage> recentMessages = new ArrayList<>();
    private int turnCount = 0;
    private final Instant createdAt;
    private Instant lastActivityAt;
    private ProcedureState activeProcedure;
    /**
     * P2.1: the explicit dialogue state for this session - pending-request
     * stack (P2.2, replaces the old single deferredIntent slot), slots,
     * obligations, correction antecedents, and the interruption signal.
     * The LLM proposes through the {@code updateDialogueState} tool;
     * deterministic code validates and applies.
     */
    private final DialogueState dialogueState = new DialogueState();
    private boolean escalated;
    private UUID pendingVerificationChallengeId;
    private ConversationFocus focus;
    private boolean toolInvokedThisTurn;
    /**
     * P2 (turn decision trace). In-memory accumulator for the current turn's
     * AI decision trace, set by {@code ConversationRuntime} at turn start and
     * cleared when the turn ends. Stage components (agent, RAG, tools,
     * coordinator) read it to record their observations; assembly is pure
     * field assignment and persistence goes through the async audit bus, so
     * it never blocks the turn. All production mutations happen inside
     * {@code SessionStore.withSession}'s per-session lock, so set-then-clear
     * within one turn is thread-safe. Never persisted as session state.
     */
    private volatile TurnTrace.Builder activeTraceBuilder;
    /**
     * P1.1: the current turn's ownership handle, installed by
     * {@code SessionStore} right after ownership acquisition and cleared
     * (identity-checked) when the turn ends. Volatile because preemption
     * transfers ownership on another thread; readers must still treat the
     * value as "the handle installed most recently", not "my handle" - a
     * turn's own handle travels through its call chain, never via this
     * field after a phase gate.
     */
    private volatile TurnHandle activeTurnHandle;


    private ConversationSession(String sessionId, Channel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.customerIdentity = CustomerIdentity.anonymous();
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    public static ConversationSession newSession(String sessionId, Channel channel) {
        return new ConversationSession(sessionId, channel);
    }

    public int recordUserMessage(String text) {
        turnCount++;
        addMessage(new ConversationMessage(MessageRole.USER, text, turnCount, Instant.now()));
        return turnCount;
    }

    public void recordAssistantMessage(String text) {
        addMessage(new ConversationMessage(MessageRole.ASSISTANT, text, turnCount, Instant.now()));
    }

    /**
     * P1.3 (history repair): records a partial assistant message - the exact
     * deltas the caller actually heard before the turn aborted. The
     * {@code partial} flag is stored metadata only: it is never spoken, and
     * the next turn's prompt sees the faithful partial transcript.
     */
    public void recordPartialAssistantMessage(String text) {
        addMessage(new ConversationMessage(MessageRole.ASSISTANT, text, turnCount, Instant.now(), true));
    }

    /**
     * P1.1: installs the current turn's ownership handle. Called by the
     * session store immediately after ownership acquisition.
     */
    public void setActiveTurnHandle(TurnHandle handle) {
        this.activeTurnHandle = handle;
    }

    /** The most recently installed turn handle, or null outside a turn. */
    public TurnHandle getActiveTurnHandle() {
        return activeTurnHandle;
    }

    /**
     * P1.1: clears the turn handle only when it is still the expected one -
     * a preempted holder's cleanup must never clear the new owner's handle.
     */
    public void clearActiveTurnHandle(TurnHandle expected) {
        if (this.activeTurnHandle == expected) {
            this.activeTurnHandle = null;
        }
    }

    private void addMessage(ConversationMessage message) {
        recentMessages.add(message);
        while (recentMessages.size() > MAX_RECENT_MESSAGES) {
            recentMessages.remove(0);
        }
    }

    public void recordAction(RecentAction action) {
        recentActions.addLast(action);
        while (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public void applyResolvedIdentity(CustomerIdentity resolved) {
        if (resolved == null) {
            return;
        }
        if (customerIdentity.assuranceLevel() == IdentityAssurance.ANONYMOUS) {
            customerIdentity = resolved;
            return;
        }
        boolean sameCustomer = resolved.customerId() != null && resolved.customerId().equals(customerIdentity.customerId());
        boolean strictlyHigherAssurance = resolved.assuranceLevel().ordinal() > customerIdentity.assuranceLevel().ordinal();
        if (sameCustomer && strictlyHigherAssurance) {
            customerIdentity = resolved;
        }
    }

    /**
     * Pass 2D-B: starts the single live procedure for this session.
     *
     * <p>There is no second slot anymore: at most one {@link ProcedureState}
     * may exist at a time, so a second live business-action/OTP/confirmation
     * authority is structurally impossible. Callers
     * ({@code ProcedureCoordinator}) decide idempotent reuse vs. deferral
     * <em>before</em> calling this.
     *
     * <p>All production mutations of a session happen inside
     * {@code SessionStore.withSession}, which holds a per-session lock, so
     * the check-then-set here is atomic with respect to other turns.
     *
     * @throws IllegalStateException if a procedure is already active
     */
    public void startActiveProcedure(ProcedureState procedure) {
        Objects.requireNonNull(procedure, "procedure");
        if (activeProcedure != null) {
            throw new IllegalStateException("An active procedure is already present; it must be completed, abandoned, or deferred first");
        }
        activeProcedure = procedure;
    }

    /**
     * Pass 2D-B: clearing the active procedure simply empties the slot. It
     * never restores anything - the paused-procedure concept is gone.
     */
    public void clearActiveProcedure() {
        activeProcedure = null;
    }

    public Optional<ProcedureState> getActiveProcedure() {
        return Optional.ofNullable(activeProcedure);
    }

    /**
     * P2.2: the top of the pending-request stack - the single deferred
     * customer intent when exactly one is queued. Kept for callers that only
     * ever need the top (prompt rendering, contested-slot checks); the stack
     * itself is on {@link #getDialogueState()}.
     */
    public Optional<DeferredProcedureIntent> getDeferredIntent() {
        return dialogueState.peekPendingRequest();
    }

    /**
     * P2.2: pushes onto the pending-request stack (max depth
     * {@link DialogueState#MAX_PENDING_REQUESTS}).
     *
     * @throws IllegalStateException if the stack is full - callers that queue
     *         on the caller's behalf (ProcedureCoordinator) check depth first
     *         and return PENDING_REQUEST_LIMIT_REACHED instead
     */
    public void setDeferredIntent(DeferredProcedureIntent intent) {
        dialogueState.pushPendingRequest(Objects.requireNonNull(intent, "intent"));
    }

    /** P2.2: pops the top of the pending-request stack, if any. */
    public void clearDeferredIntent() {
        dialogueState.popPendingRequest();
    }

    /** P2.1: the session's explicit dialogue state. */
    public DialogueState getDialogueState() {
        return dialogueState;
    }

    public void markEscalated() {
        this.escalated = true;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public void setPendingVerificationChallengeId(UUID challengeId) {
        this.pendingVerificationChallengeId = challengeId;
    }

    public Optional<UUID> getPendingVerificationChallengeId() {
        return Optional.ofNullable(pendingVerificationChallengeId);
    }

    public void clearPendingVerification() {
        this.pendingVerificationChallengeId = null;
    }

    /** Switching to a genuinely different order clears any item pinned under the previous one. */
    public void recordFocusOrder(String orderNumber) {
        if (orderNumber == null) {
            return;
        }
        if (focus == null || !orderNumber.equals(focus.orderNumber())) {
            focus = ConversationFocus.ofOrder(orderNumber);
        }
    }

    public void recordFocusItem(String itemSku, String itemDisplayName) {
        focus = focus == null ? new ConversationFocus(null, itemSku, itemDisplayName) : focus.withItem(itemSku, itemDisplayName);
    }

    public Optional<ConversationFocus> getFocus() {
        return Optional.ofNullable(focus);
    }

    public void touch() {
        lastActivityAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public CustomerIdentity getCustomerIdentity() {
        return customerIdentity;
    }

    public List<RecentAction> getRecentActions() {
        return List.copyOf(recentActions);
    }

    public List<ConversationMessage> getRecentMessages() {
        return List.copyOf(recentMessages);
    }

    public int getTurnCount() {
        return turnCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }



    /** P2: the current turn's trace accumulator, or null when no turn is in flight. */
    public TurnTrace.Builder getActiveTraceBuilder() {
        return activeTraceBuilder;
    }

    /** P2: installed by ConversationRuntime at turn start, cleared at turn end. */
    public void setActiveTraceBuilder(TurnTrace.Builder builder) {
        this.activeTraceBuilder = builder;
    }

    /** Diagnostic only (proposal #3): lets ConversationRuntime detect a response that claims a system failure with no tool ever attempted. */
    public void resetToolInvokedFlag() {
        toolInvokedThisTurn = false;
    }

    public void markToolInvoked() {
        toolInvokedThisTurn = true;
    }

    public boolean wasToolInvokedThisTurn() {
        return toolInvokedThisTurn;
    }
}
