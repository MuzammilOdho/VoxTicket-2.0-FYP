package com.voxticket.agent;

import com.voxticket.conversation.ConversationMessage;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * Spec §5 (ContextBuilder). Assembles conversation history into Spring AI
 * {@link Message} objects only. Store-policy knowledge is NOT prefetched
 * into the context - the model searches it on demand via the searchPolicy
 * tool (Phase 8, tool-based RAG). The returned list already includes the
 * current turn's user message (recorded into the session before the agent
 * is called), so callers should pass this directly as {@code .messages(...)}
 * without also separately appending the current message.
 */
@Component
public class ContextBuilder {

    /**
     * Maximum conversation messages sent to the LLM per turn. The session itself keeps a longer
     * history ({@code MAX_RECENT_MESSAGES}) for audit and reference resolution - this bound only
     * limits what the model sees, and always includes the current user message (recorded into the
     * session before the agent is called).
     */
    private static final int MAX_MODEL_MESSAGES = 10;

    public List<Message> buildHistory(ConversationSession session) {
        return buildHistory(session, null);
    }

    /**
     * Pass 2D cleanup: builds the model message list with an optional override
     * for the latest USER message of the current turn. The override applies
     * ONLY to the model input - the session, audit records, turn count, and
     * the {@value #MAX_MODEL_MESSAGES}-message model-history cap are
     * untouched, and no duplicate user message is added.
     *
     * <p>This makes {@code SupportAgent.respond(session, currentUserMessage)}
     * truthful: the supplied message is what the model actually receives for
     * the current turn. For normal turns the override equals the stored
     * message and behavior is unchanged; for sensitive residual turns the
     * model sees the residual text only, while session/audit keep the safe
     * redacted full message.
     */
    public List<Message> buildHistory(ConversationSession session, String currentUserMessageOverride) {
        List<ConversationMessage> recent = session.getRecentMessages();
        int from = Math.max(0, recent.size() - MAX_MODEL_MESSAGES);
        List<ConversationMessage> window = recent.subList(from, recent.size());
        List<Message> messages = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            ConversationMessage conversationMessage = window.get(i);
            boolean isLatestUserMessage = currentUserMessageOverride != null
                    && conversationMessage.role() == MessageRole.USER
                    && i == window.size() - 1;
            messages.add(isLatestUserMessage
                    ? new UserMessage(currentUserMessageOverride)
                    : toSpringAiMessage(conversationMessage));
        }
        return List.copyOf(messages);
    }

    private Message toSpringAiMessage(ConversationMessage message) {
        return message.role() == MessageRole.USER ? new UserMessage(message.text()) : new AssistantMessage(message.text());
    }
}