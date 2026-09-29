package com.voxticket.conversation;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
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
     * Pass 2D-B: at most ONE deferred customer mutation intent, and it is
     * not a {@link ProcedureState} - it carries no OTP, no challenge, no
     * confirmation authority, and no execution permission. See
     * {@link DeferredProcedureIntent}.
     */
    private DeferredProcedureIntent deferredIntent;
    private boolean escalated;
    private UUID pendingVerificationChallengeId;
    private ConversationFocus focus;
    private boolean toolInvokedThisTurn;


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
     * Pass 2D-B: the single deferred customer intent, if one was queued
     * while another procedure was active. Never a live procedure.
     */
    public Optional<DeferredProcedureIntent> getDeferredIntent() {
        return Optional.ofNullable(deferredIntent);
    }

    /**
     * Queues the deferred intent. Refuses to overwrite an occupied slot -
     * the coordinator returns {@code PENDING_REQUEST_LIMIT_REACHED} instead.
     *
     * @throws IllegalStateException if a deferred intent is already queued
     */
    public void setDeferredIntent(DeferredProcedureIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (deferredIntent != null) {
            throw new IllegalStateException("A deferred intent is already queued; discard it first");
        }
        deferredIntent = intent;
    }

    public void clearDeferredIntent() {
        deferredIntent = null;
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
