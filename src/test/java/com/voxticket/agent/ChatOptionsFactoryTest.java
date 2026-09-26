package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChatOptionsFactoryTest {

    private final ChatOptionsFactory factory = new ChatOptionsFactory();

    private static TierChatProperties tier(String reasoningEffort) {
        return new TierChatProperties(AiProvider.GROQ, "openai/gpt-oss-20b", 0.3, 1024, reasoningEffort, 20);
    }

    @Test
    void tierValuesAreMappedToChatOptions() {
        var options = factory.forTier(new TierChatProperties(AiProvider.CEREBRAS, "gpt-oss-120b", 0.5, 1536, "high", 45));

        assertThat(options.getModel()).isEqualTo("gpt-oss-120b");
        assertThat(options.getTemperature()).isEqualTo(0.5);
        assertThat(options.getMaxCompletionTokens()).isEqualTo(1536);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void noneReasoningEffortSuppressesReasoningInsteadOfPassingItThrough() {
        var options = factory.forTier(tier("none"));

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody()).containsEntry("include_reasoning", false);
    }

    @Test
    void explicitReasoningEffortIsPassedThroughNatively() {
        var options = factory.forTier(tier("high"));

        assertThat(options.getReasoningEffort()).isEqualTo("high");
        // No suppression marker when an explicit effort is requested.
        assertThat(options.getExtraBody()).isNull();
    }
}
