package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ContextBuilderTest {

    private final ContextBuilder contextBuilder = new ContextBuilder();

    @Test
    void buildsHistoryInOrderWithCorrectMessageTypes() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        session.recordUserMessage("hello");
        session.recordAssistantMessage("hi there");
        session.recordUserMessage("where is my order");

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history.get(2)).isInstanceOf(UserMessage.class);
    }

    @Test
    void emptySessionProducesEmptyHistory() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(contextBuilder.buildHistory(session)).isEmpty();
    }
}