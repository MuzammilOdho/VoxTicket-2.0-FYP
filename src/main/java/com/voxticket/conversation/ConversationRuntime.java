package com.voxticket.conversation;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Spec §3.2/§4. The one place phone and chat both funnel through -
 * `ChatController` (this phase) and, from Phase 11 on, the voice gateway
 * both do nothing more than build a {@link UserTurn} and call
 * {@link #processTurn}. There must never be a parallel "chat version" or
 * "voice version" of anything below this method.
 *
 * <p>No AI yet (Phase 5). This phase's job is the plumbing - session
 * lookup/creation/locking, identity resolution, turn/message bookkeeping -
 * proven correct on its own, with an honestly-labeled stub reply rather
 * than a fake attempt at understanding.
 */
@Service
public class ConversationRuntime {

    private final SessionStore sessionStore;
    private final IdentityService identityService;

    public ConversationRuntime(SessionStore sessionStore, IdentityService identityService) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
    }

    public AssistantTurn processTurn(UserTurn turn) {
        return sessionStore.withSession(turn.sessionId(), turn.channel(), session -> {
            if (StringUtils.hasText(turn.callerPhone())) {
                CustomerIdentity resolved = identityService.resolveByPhone(turn.callerPhone());
                session.applyResolvedIdentity(resolved);
            }

            int turnNumber = session.recordUserMessage(turn.text());
            String responseText = placeholderResponse(turnNumber);
            session.recordAssistantMessage(responseText);

            ConversationStateView stateView = new ConversationStateView(
                    session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel(), turnNumber);

            return new AssistantTurn(responseText, false, false, stateView, Map.of());
        });
    }

    private String placeholderResponse(int turnNumber) {
        return "[stub] Message received (turn " + turnNumber + "). AI-based understanding is not wired in until Phase 5 - "
                + "this response only proves the shared session/turn pipeline works end to end.";
    }
}