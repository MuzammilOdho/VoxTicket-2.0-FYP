package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.api.chat.ChatResponse;
import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.ConversationStateView;
import com.voxticket.conversation.SessionBusyException;
import com.voxticket.conversation.SessionStore;
import com.voxticket.conversation.UserTurn;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.observability.TraceIds;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Pure unit test (no Spring context): the voice turn endpoint must build a
 * PHONE-channel turn with no caller-asserted phone and map the assistant
 * turn back to the chat response shape the LiveKit worker consumes.
 */
@ExtendWith(MockitoExtension.class)
class VoiceTurnControllerTest {

    @Mock
    private ConversationRuntime conversationRuntime;

    @Mock
    private SessionStore sessionStore;

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

        ChatResponse response = controller.turn(new VoiceTurnRequest("voice-room-1", "hello"), new MockHttpServletRequest());

        verify(conversationRuntime).processTurn(captor.capture());
        UserTurn turn = captor.getValue();
        assertThat(turn.channel()).isEqualTo(Channel.PHONE);
        assertThat(turn.callerPhone()).isNull();
        assertThat(turn.sessionId()).isEqualTo("voice-room-1");
        assertThat(turn.text()).isEqualTo("hello");
        assertThat(turn.providerMetadata()).containsKey(TraceIds.METADATA_TRACE_ID);
        assertThat(turn.providerMetadata().get(TraceIds.METADATA_TRACE_ID)).matches("[0-9a-f]{32}");

        assertThat(response.sessionId()).isEqualTo("voice-room-1");
        assertThat(response.text()).isEqualTo("stubbed voice reply");
        assertThat(response.turnNumber()).isEqualTo(1);
        assertThat(response.identityAssurance()).isEqualTo("ANONYMOUS");
    }

    @Test
    void sessionBusyPropagatesAsThe429MappedException() {
        // The blocking endpoint does not swallow the exception: Spring
        // resolves it to 429 via the @ResponseStatus on SessionBusyException.
        when(conversationRuntime.processTurn(any())).thenThrow(new SessionBusyException("voice-room-1", 2000));

        assertThatThrownBy(() ->
                        controller.turn(new VoiceTurnRequest("voice-room-1", "hello"), new MockHttpServletRequest()))
                .isInstanceOf(SessionBusyException.class);
        assertThat(SessionBusyException.class.getAnnotation(ResponseStatus.class).value())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
