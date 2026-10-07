package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.UserTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the SSE contract of {@code POST /api/v1/voice/turn/stream} with a
 * MockMvc standalone setup (spring-test only - no Spring Boot test slice):
 * one {@code data: {"delta": "..."}} per emitted delta, a final
 * {@code data: {"done": true}}, the {@link UserTurn} the controller builds,
 * and a quiet completion when the client disconnects mid-stream.
 */
class VoiceTurnStreamControllerTest {

    private ConversationRuntime runtime;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        runtime = mock(ConversationRuntime.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VoiceTurnController(runtime)).build();
    }

    @Test
    void streamEmitsDeltasThenDone() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(1);
            sink.accept("Hello");
            sink.accept(" world");
            return new AssistantTurn("Hello world", false, false, null, Map.of());
        }).when(runtime).processTurnStream(any(UserTurn.class), any());

        MvcResult started = mockMvc.perform(post("/api/v1/voice/turn/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        String body = started.getResponse().getContentAsString();
        assertThat(body).contains("data:");
        assertThat(body).contains("\"delta\":\"Hello\"");
        assertThat(body).contains("\"delta\":\" world\"");
        assertThat(body).contains("\"done\":true}");
        assertThat(body.indexOf("\"delta\":\"Hello\""))
                .isLessThan(body.indexOf("\"delta\":\" world\""));
        assertThat(body.indexOf("\"delta\":\" world\""))
                .isLessThan(body.indexOf("\"done\":true"));

        ArgumentCaptor<UserTurn> turnCaptor = ArgumentCaptor.forClass(UserTurn.class);
        verify(runtime).processTurnStream(turnCaptor.capture(), any());
        UserTurn turn = turnCaptor.getValue();
        assertThat(turn.sessionId()).isEqualTo("sess-1");
        assertThat(turn.channel()).isEqualTo(Channel.PHONE);
        assertThat(turn.text()).isEqualTo("hi");
        assertThat(turn.callerPhone()).isNull();
    }

    @Test
    void clientDisconnectMidStreamCompletesQuietly() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(1);
            sink.accept("partial");
            throw new TurnAbortedException(new IOException("client gone"));
        }).when(runtime).processTurnStream(any(UserTurn.class), any());

        MvcResult started = mockMvc.perform(post("/api/v1/voice/turn/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-2\",\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // No error status: the abort is logged at debug and the stream completes.
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        String body = started.getResponse().getContentAsString();
        assertThat(body).contains("\"delta\":\"partial\"");
        assertThat(body).doesNotContain("\"done\":true");
    }
}
