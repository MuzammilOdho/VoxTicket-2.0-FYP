package com.voxticket.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.procedure.ProcedureType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionPolicyServiceTest {

    private final ActionPolicyService policyService = new ActionPolicyService();

    @Test
    void insufficientAssuranceRequiresVerificationRegardlessOfConfirmation() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        PolicyDecision decision = policyService.evaluate(new ActionRequest(ProcedureType.CANCELLATION, IdentityAssurance.OTP_VERIFIED, false), session);

        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.REQUIRE_VERIFICATION);
    }

    @Test
    void sufficientAssuranceWithoutConfirmationRequiresConfirmation() {
        ConversationSession session = ConversationSession.newSession("s2", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        PolicyDecision decision = policyService.evaluate(new ActionRequest(ProcedureType.CLAIM, IdentityAssurance.PHONE_MATCHED, false), session);

        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.REQUIRE_CONFIRMATION);
    }

    @Test
    void sufficientAssuranceWithConfirmationAllows() {
        ConversationSession session = ConversationSession.newSession("s3", Channel.CHAT);
        session.applyResolvedIdentity(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.OTP_VERIFIED, "+923001234567"));

        PolicyDecision decision = policyService.evaluate(new ActionRequest(ProcedureType.CANCELLATION, IdentityAssurance.OTP_VERIFIED, true), session);

        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.ALLOW);
    }
}