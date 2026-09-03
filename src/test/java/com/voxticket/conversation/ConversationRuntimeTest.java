package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.IdentityService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationRuntimeTest {

    private final IdentityService identityService = mock(IdentityService.class);
    private final ConversationRuntime runtime = new ConversationRuntime(new InMemorySessionStore(), identityService);

    @Test
    void firstTurnWithNoPhoneStaysAnonymous() {
        UserTurn turn = new UserTurn("s1", Channel.CHAT, "hello", null, Instant.now(), Map.of());

        AssistantTurn response = runtime.processTurn(turn);

        assertThat(response.conversationState().identityAssurance()).isEqualTo(IdentityAssurance.ANONYMOUS);
        assertThat(response.conversationState().turnNumber()).isEqualTo(1);
        assertThat(response.requiresVerification()).isFalse();
        assertThat(response.requiresConfirmation()).isFalse();
    }

    @Test
    void aRecognizedPhoneResolvesToPhoneMatched() {
        UUID customerId = UUID.randomUUID();
        when(identityService.resolveByPhone("+923001234567"))
                .thenReturn(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        UserTurn turn = new UserTurn("s2", Channel.CHAT, "where is my order", "+923001234567", Instant.now(), Map.of());
        AssistantTurn response = runtime.processTurn(turn);

        assertThat(response.conversationState().identityAssurance()).isEqualTo(IdentityAssurance.PHONE_MATCHED);
    }

    @Test
    void turnNumberIncrementsAcrossSuccessiveTurnsOnTheSameSession() {
        UserTurn first = new UserTurn("s3", Channel.CHAT, "hi", null, Instant.now(), Map.of());
        UserTurn second = new UserTurn("s3", Channel.CHAT, "still there?", null, Instant.now(), Map.of());

        runtime.processTurn(first);
        AssistantTurn secondResponse = runtime.processTurn(second);

        assertThat(secondResponse.conversationState().turnNumber()).isEqualTo(2);
    }

    @Test
    void identityIsNotReResolvedOnceAnchoredEvenIfALaterTurnOmitsThePhone() {
        UUID customerId = UUID.randomUUID();
        when(identityService.resolveByPhone("+923001234567"))
                .thenReturn(new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        runtime.processTurn(new UserTurn("s4", Channel.CHAT, "hi", "+923001234567", Instant.now(), Map.of()));
        AssistantTurn secondResponse = runtime.processTurn(new UserTurn("s4", Channel.CHAT, "still me", null, Instant.now(), Map.of()));

        assertThat(secondResponse.conversationState().identityAssurance()).isEqualTo(IdentityAssurance.PHONE_MATCHED);
    }
}