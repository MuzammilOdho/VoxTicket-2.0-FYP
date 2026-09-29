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

    @Test
    void fewerThanTenMessagesAreAllPreservedInOrder() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (int i = 0; i < 7; i++) {
            session.recordUserMessage("user-" + i);
        }

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(history.get(i)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) history.get(i)).getText()).isEqualTo("user-" + i);
        }
    }

    @Test
    void moreThanTenMessagesKeepsOnlyTheLatestTen() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (int i = 0; i < 15; i++) {
            session.recordUserMessage("user-" + i);
        }

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(10);
        assertThat(((UserMessage) history.get(0)).getText()).isEqualTo("user-5");
        assertThat(((UserMessage) history.get(9)).getText()).isEqualTo("user-14");
    }

    @Test
    void theCurrentUserMessageRemainsPresentWhenHistoryIsTrimmed() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (int i = 0; i < 14; i++) {
            session.recordAssistantMessage("assistant-" + i);
        }
        session.recordUserMessage("current question");

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(10);
        var last = history.get(9);
        assertThat(last).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) last).getText()).isEqualTo("current question");
    }

    @Test
    void trimmedHistoryKeepsCorrectSpringAiMessageRoles() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (int i = 0; i < 6; i++) {
            session.recordUserMessage("q-" + i);
            session.recordAssistantMessage("a-" + i);
        }

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(10);
        // The two oldest messages (q-0, a-0) fall outside the 10-message window.
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) history.get(0)).getText()).isEqualTo("q-1");
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) history.get(1)).getText()).isEqualTo("a-1");
        assertThat(history.get(9)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) history.get(9)).getText()).isEqualTo("a-5");
    }

    @Test
    void exactlyTenMessagesAreAllPreserved() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        for (int i = 0; i < 10; i++) {
            session.recordUserMessage("user-" + i);
        }

        var history = contextBuilder.buildHistory(session);

        assertThat(history).hasSize(10);
        assertThat(((UserMessage) history.get(0)).getText()).isEqualTo("user-0");
        assertThat(((UserMessage) history.get(9)).getText()).isEqualTo("user-9");
    }
}