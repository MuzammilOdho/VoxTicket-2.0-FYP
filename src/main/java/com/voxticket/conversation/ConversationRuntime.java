package com.voxticket.conversation;

import com.voxticket.agent.SupportAgent;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Spec §3.2/§4. The one place phone and chat both funnel through -
 * `ChatController` and, from Phase 11 on, the voice gateway both do nothing
 * more than build a {@link UserTurn} and call {@link #processTurn}.
 *
 * <p>As of this phase, real responses come from {@link SupportAgent}
 * instead of the Phase 4 placeholder - session lookup/creation/locking and
 * identity resolution are unchanged.
 */
@Service
public class ConversationRuntime {

    private final SessionStore sessionStore;
    private final IdentityService identityService;
    private final SupportAgent supportAgent;

    public ConversationRuntime(SessionStore sessionStore, IdentityService identityService, SupportAgent supportAgent) {
        this.sessionStore = sessionStore;
        this.identityService = identityService;
        this.supportAgent = supportAgent;
    }

    public AssistantTurn processTurn(UserTurn turn) {
        return sessionStore.withSession(turn.sessionId(), turn.channel(), session -> {
            if (StringUtils.hasText(turn.callerPhone())) {
                CustomerIdentity resolved = identityService.resolveByPhone(turn.callerPhone());
                session.applyResolvedIdentity(resolved);
            }

            int turnNumber = session.recordUserMessage(turn.text());
            String responseText = supportAgent.respond(session, turn.text());
            session.recordAssistantMessage(responseText);

            ConversationStateView stateView = new ConversationStateView(
                    session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel(), turnNumber);

            return new AssistantTurn(responseText, false, false, stateView, Map.of());
        });
    }
}