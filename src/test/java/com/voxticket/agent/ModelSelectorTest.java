package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import org.junit.jupiter.api.Test;

class ModelSelectorTest {

    private final ModelSelector modelSelector = new ModelSelector("openai/gpt-oss-20b", "openai/gpt-oss-120b");

    @Test
    void shortSimpleMessageStaysOnTierOne() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(modelSelector.select(session, "Where is my order?")).isEqualTo(ModelTier.TIER_1);
    }

    @Test
    void veryLongMessageEscalatesToTierTwo() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
        String longMessage = "I need help with my order. ".repeat(20);

        assertThat(modelSelector.select(session, longMessage)).isEqualTo(ModelTier.TIER_2);
    }

    @Test
    void mentioningMultipleOrderReferencesEscalatesToTierTwo() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(modelSelector.select(session, "What's happening with ORD-10001 and also ORD-10002?")).isEqualTo(ModelTier.TIER_2);
    }

    @Test
    void conditionalLanguageEscalatesToTierTwo() {
        ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);

        assertThat(modelSelector.select(session, "Cancel my order unless it has already shipped.")).isEqualTo(ModelTier.TIER_2);
    }

    @Test
    void modelForReturnsTheConfiguredModelNames() {
        assertThat(modelSelector.modelFor(ModelTier.TIER_1)).isEqualTo("openai/gpt-oss-20b");
        assertThat(modelSelector.modelFor(ModelTier.TIER_2)).isEqualTo("openai/gpt-oss-120b");
    }
}