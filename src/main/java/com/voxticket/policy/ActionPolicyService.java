package com.voxticket.policy;

import com.voxticket.conversation.ConversationSession;
import org.springframework.stereotype.Service;

/**
 * Spec §19. A SMALL deterministic trust boundary - identity assurance vs.
 * confirmation status only. It does NOT decide domain eligibility (that's
 * CancellationPolicyService/ReturnPolicyService, Phase 3) and does NOT
 * decide ownership (that's already proven by the time a VerifiedOrderRef
 * exists, Phase 2). Keeping those out of here is what stops this from
 * becoming the generic rules engine spec §19 explicitly warns against.
 */
@Service
public class ActionPolicyService {

    public PolicyDecision evaluate(ActionRequest request, ConversationSession session) {
        if (!session.getCustomerIdentity().isAtLeast(request.requiredAssurance())) {
            return PolicyDecision.requireVerification(
                    "Requires " + request.requiredAssurance() + " but caller has " + session.getCustomerIdentity().assuranceLevel());
        }
        if (!request.alreadyConfirmed()) {
            return PolicyDecision.requireConfirmation();
        }
        return PolicyDecision.allow();
    }
}