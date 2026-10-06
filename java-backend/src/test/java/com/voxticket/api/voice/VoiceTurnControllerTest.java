package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.api.chat.ChatResponse;
import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.ConversationStateView;
import com.voxticket.conversation.UserTurn;
import com.voxticket.identity.IdentityAssurance;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test (no Spring context): the voice turn endpoint must build a
 * PHONE-channel turn with no caller-asserted phone and map the assistant
 * turn back to the chat response shape the LiveKit worker consumes.
 */
@ExtendWith(MockitoExtension.class)
class VoiceTurnControllerTest {

    @Mock
    private ConversationRuntime conversationRuntime;

    @InjectMocks
    private VoiceTurnController controller;

    @Test
    void voiceTurnBuildsPhoneChannelTurnWithNoCallerPhone() {
        ArgumentCaptor<UserTurn> captor = ArgumentCaptor.forClass(UserTurn.class);
        when(conversationRuntime.processTurn(any())).thenAnswer(invocation -> {
            UserTurn turn = invocation.getArgument(0);
            return new AssistantTurn(
                    "stubbed voice reply",
                    false,
                    false,
                    new ConversationStateView(
                            turn.sessionId(), turn.channel(), IdentityAssurance.ANONYMOUS, 1),
                    Map.of());
        });

        ChatResponse response = controller.turn(new VoiceTurnRequest("voice-room-1", "hello"));

        verify(conversationRuntime).processTurn(captor.capture());
        UserTurn turn = captor.getValue();
        assertThat(turn.channel()).isEqualTo(Channel.PHONE);
        assertThat(turn.callerPhone()).isNull();
        assertThat(turn.sessionId()).isEqualTo("voice-room-1");
        assertThat(turn.text()).isEqualTo("hello");

        assertThat(response.sessionId()).isEqualTo("voice-room-1");
        assertThat(response.text()).isEqualTo("stubbed voice reply");
        assertThat(response.turnNumber()).isEqualTo(1);
        assertThat(response.identityAssurance()).isEqualTo("ANONYMOUS");
    }
}
