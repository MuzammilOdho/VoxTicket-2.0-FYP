package com.voxticket.conversation;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.procedure.ProcedureSlotResult;
import com.voxticket.procedure.ProcedureState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ConversationSession {

    private static final int MAX_RECENT_ACTIONS = 10;
    private static final int MAX_RECENT_MESSAGES = 40;

    private final String sessionId;
    private final Channel channel;
    private String providerSessionId;
    private String language;
    private CustomerIdentity customerIdentity;
    private final EntityContext entityContext = new EntityContext();
    private final Deque<RecentAction> recentActions = new ArrayDeque<>();
    private final List<ConversationMessage> recentMessages = new ArrayList<>();
    private int turnCount = 0;
    private final Instant createdAt;
    private Instant lastActivityAt;
    private ProcedureState activeProcedure;
    private ProcedureState pausedProcedure;
    private boolean escalated;
    private UUID pendingVerificationChallengeId;

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

    public ProcedureSlotResult beginProcedure(ProcedureState newProcedure) {
        if (activeProcedure == null) {
            activeProcedure = newProcedure;
            return ProcedureSlotResult.STARTED;
        }
        if (pausedProcedure == null) {
            pausedProcedure = activeProcedure;
            activeProcedure = newProcedure;
            return ProcedureSlotResult.STARTED_AND_PAUSED_PREVIOUS;
        }
        return ProcedureSlotResult.BOTH_SLOTS_OCCUPIED;
    }

    public void clearActiveProcedure() {
        activeProcedure = pausedProcedure;
        pausedProcedure = null;
    }

    public Optional<ProcedureState> getActiveProcedure() {
        return Optional.ofNullable(activeProcedure);
    }

    public Optional<ProcedureState> getPausedProcedure() {
        return Optional.ofNullable(pausedProcedure);
    }

    public void markEscalated() {
        this.escalated = true;
    }

    public boolean isEscalated() {
        return escalated;
    }

    /** Spec §6 "pendingVerification" - a lightweight reference only; the VerificationChallenge row in the database is the authoritative record. */
    public void setPendingVerificationChallengeId(UUID challengeId) {
        this.pendingVerificationChallengeId = challengeId;
    }

    public Optional<UUID> getPendingVerificationChallengeId() {
        return Optional.ofNullable(pendingVerificationChallengeId);
    }

    public void clearPendingVerification() {
        this.pendingVerificationChallengeId = null;
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

    public String getProviderSessionId() {
        return providerSessionId;
    }

    public void setProviderSessionId(String providerSessionId) {
        this.providerSessionId = providerSessionId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public CustomerIdentity getCustomerIdentity() {
        return customerIdentity;
    }

    public EntityContext getEntityContext() {
        return entityContext;
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
}