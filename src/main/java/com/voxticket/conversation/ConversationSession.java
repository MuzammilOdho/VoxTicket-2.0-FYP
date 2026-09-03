package com.voxticket.conversation;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Spec §6. Channel-neutral, in-memory conversational state - never a JPA
 * entity, never backed by PostgreSQL as the live store (spec §7).
 *
 * <p>Not thread-safe by itself. Every access must go through
 * {@link SessionStore#withSession}, which is the only thing that ever holds
 * a reference to an instance of this class.
 */
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

    private ConversationSession(String sessionId, Channel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.customerIdentity = CustomerIdentity.anonymous();
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    static ConversationSession newSession(String sessionId, Channel channel) {
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

    /**
     * Resolution happens at most once per session. If still ANONYMOUS, any
     * resolution result is accepted - including another ANONYMOUS result,
     * which is a legitimate outcome for an unrecognized number, not an
     * error. Once anchored to a specific customer, only a strictly higher
     * assurance level for that SAME customer is accepted; attempts to
     * switch to a different customer, or to downgrade, are silently
     * ignored. This is what stops one chat session from re-labeling itself
     * as a different customer mid-conversation just by sending a different
     * customerPhone on a later request.
     */
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