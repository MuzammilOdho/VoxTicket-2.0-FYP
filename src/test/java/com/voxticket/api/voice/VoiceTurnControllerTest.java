package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.json.JsonMapper;
import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.ConversationStateView;
import com.voxticket.identity.IdentityAssurance;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The voice endpoint speaks SSE only to callers presenting the configured
 * service key, and translates the runtime's deltas into delta/done events.
 * Uses standalone MockMvc so no extra test slice dependency is needed.
 */
@ExtendWith(MockitoExtension.class)
class VoiceTurnControllerTest {

    private static final String PATH = "/api/v1/voice/turn/stream";
    private static final String KEY_HEADER = "X-Voice-Service-Key";

    @Mock
    private ConversationRuntime runtime;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new VoiceTurnController(runtime, new JsonMapper(), "test-voice-key");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice()
                .build();

        Answer<AssistantTurn> answer = inv -> {
            Consumer<String> sink = inv.getArgument(1);
            sink.accept("hello ");
            sink.accept("world");
            return new AssistantTurn("hello world", false, false,
                    new ConversationStateView("voice-1", Channel.PHONE, IdentityAssurance.ANONYMOUS, 1), Map.of());
        };
        lenient().when(runtime.streamTurn(any(), any())).thenAnswer(answer);
    }

    private String sseBody(String apiKey) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"voice-1\",\"text\":\"hello\"}");
        if (apiKey != null) {
            request.header(KEY_HEADER, apiKey);
        }
        MvcResult started = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        return started.getResponse().getContentAsString();
    }

    @Test
    void validKeyStreamsDeltaAndDoneEvents() throws Exception {
        String body = sseBody("test-voice-key");

        assertThat(body).contains("event:delta");
        assertThat(body).contains("hello ");
        assertThat(body).contains("event:done");
        assertThat(body).contains("voice-1");
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(KEY_HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }
}
    