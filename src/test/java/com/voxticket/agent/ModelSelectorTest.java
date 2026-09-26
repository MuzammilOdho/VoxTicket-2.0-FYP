package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

class ModelSelectorTest {

    private final ModelSelector modelSelector = new ModelSelector(new ModelSelectorProperties(300, 2));

    @Test
    void shortSimpleMessageStaysOnTierOneWithDefaultReason() {
        var result = modelSelector.select(ConversationSession.newSession("s1", Channel.CHAT), "Where is my order?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_1);
        assertThat(result.reason()).isEqualTo("default");
    }

    @Test
    void veryLongMessageEscalatesToTierTwoWithLongMessageReason() {
        String longMessage = "I need help with my order. ".repeat(20);

        var result = modelSelector.select(ConversationSession.newSession("s1", Channel.CHAT), longMessage);

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("long_message");
    }

    @Test
    void mentioningMultipleOrderReferencesEscalatesToTierTwoWithMultiOrderReason() {
        var result = modelSelector.select(ConversationSession.newSession("s1", Channel.CHAT), "What's happening with ORD-10001 and also ORD-10002?");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("multi_order_reference");
    }

    @Test
    void conditionalLanguageEscalatesToTierTwoWithConditionalReason() {
        var result = modelSelector.select(ConversationSession.newSession("s1", Channel.CHAT), "Cancel my order unless it has already shipped.");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("conditional_language");
    }

    @Test
    void selectionResultCarriesOnlyTierAndRoutingReason() {
        var result = modelSelector.select(ConversationSession.newSession("s1", Channel.CHAT), "Where is my order?");

        assertThat(result).isInstanceOf(ModelSelectionResult.class);
        assertThat(ModelSelectionResult.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("tier", "reason");
    }

    @Test
    void selectorExposesNoProviderOrModelLookup() {
        assertThat(Arrays.stream(ModelSelector.class.getMethods()).map(m -> m.getName()))
                .doesNotContain("modelFor", "providerFor", "model", "provider");
    }

    @Test
    void selectorThresholdsAreConfigurableIndependentlyOfTiers() {
        ModelSelector tightSelector = new ModelSelector(new ModelSelectorProperties(10, 2));

        var result = tightSelector.select(ConversationSession.newSession("s1", Channel.CHAT), "This message is definitely over ten characters.");

        assertThat(result.tier()).isEqualTo(ModelTier.TIER_2);
        assertThat(result.reason()).isEqualTo("long_message");
    }
    
}