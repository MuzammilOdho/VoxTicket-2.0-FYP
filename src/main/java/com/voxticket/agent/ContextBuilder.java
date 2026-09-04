package com.voxticket.agent;

import com.voxticket.conversation.ConversationMessage;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * Spec §5 (ContextBuilder). For this phase, assembles conversation history
 * into Spring AI {@link Message} objects only - no RAG prefetch yet
 * (Phase 6). The returned list already includes the current turn's user
 * message (recorded into the session before the agent is called), so
 * callers should pass this directly as {@code .messages(...)} without also
 * separately appending the current message.
 */
@Component
public class ContextBuilder {

    public List<Message> buildHistory(ConversationSession session) {
        return session.getRecentMessages().stream().<Message>map(this::toSpringAiMessage).toList();
    }

    private Message toSpringAiMessage(ConversationMessage message) {
        return message.role() == MessageRole.USER ? new UserMessage(message.text()) : new AssistantMessage(message.text());
    }
}