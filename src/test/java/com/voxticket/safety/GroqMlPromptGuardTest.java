package com.voxticket.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

class GroqMlPromptGuardTest {

    private final PromptGuard fallback = mock(PromptGuard.class);

    @Test
    void blankInputIsAllowedWithoutCallingTheModel() {
        ChatClient chatClient = mock(ChatClient.class);

        GroqMlPromptGuard guard = new GroqMlPromptGuard(
                chatClient,
                fallback,
                "meta-llama/llama-prompt-guard-2-86m",
                0.5
        );

        assertThat(guard.evaluate("").suspicious()).isFalse();

        verifyNoInteractions(chatClient);
    }

    @Test
    void fallsBackToHeuristicWhenTheModelCallThrows() {
        ChatClient chatClient = mock(ChatClient.class);

        when(chatClient.prompt())
                .thenThrow(new RuntimeException("network error"));

        when(fallback.evaluate("test message"))
                .thenReturn(PromptGuardVerdict.allow());

        GroqMlPromptGuard guard = new GroqMlPromptGuard(
                chatClient,
                fallback,
                "meta-llama/llama-prompt-guard-2-86m",
                0.5
        );

        assertThat(guard.evaluate("test message").suspicious()).isFalse();

        verify(fallback).evaluate("test message");
    }

    @Test
    void parseScoreAcceptsValidScoresInRange() {
        assertThat(GroqMlPromptGuard.parseScore("0.87")).isEqualTo(0.87);
        assertThat(GroqMlPromptGuard.parseScore("0")).isEqualTo(0.0);
        assertThat(GroqMlPromptGuard.parseScore("1")).isEqualTo(1.0);
        assertThat(GroqMlPromptGuard.parseScore("  0.5  ")).isEqualTo(0.5);
    }

    @Test
    void parseScoreRejectsOutOfRangeValues() {
        assertThat(GroqMlPromptGuard.parseScore("1.5")).isNull();
        assertThat(GroqMlPromptGuard.parseScore("-0.1")).isNull();
    }

    @Test
    void parseScoreRejectsBlankOrUnparseableOutputRatherThanAssumingBenign() {
        assertThat(GroqMlPromptGuard.parseScore(null)).isNull();
        assertThat(GroqMlPromptGuard.parseScore("")).isNull();
        assertThat(GroqMlPromptGuard.parseScore("benign")).isNull();
        assertThat(GroqMlPromptGuard.parseScore("not a number")).isNull();
    }

    @Test
    void scoreAtOrAboveThresholdIsFlaggedBelowIsAllowed() {
        ChatClient chatClient = mock(ChatClient.class);

        ChatClient.ChatClientRequestSpec highRequest =
                mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec lowRequest =
                mock(ChatClient.ChatClientRequestSpec.class);

        ChatClient.CallResponseSpec highResponse =
                mock(ChatClient.CallResponseSpec.class);
        ChatClient.CallResponseSpec lowResponse =
                mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt())
                .thenReturn(highRequest, lowRequest);

        when(highRequest.user("high risk"))
                .thenReturn(highRequest);
        when(highRequest.options(any(ChatOptions.builder().getClass())))
                .thenReturn(highRequest);
        when(highRequest.call())
                .thenReturn(highResponse);
        when(highResponse.content())
                .thenReturn("0.9");

        when(lowRequest.user("low risk"))
                .thenReturn(lowRequest);
        when(lowRequest.options(any(ChatOptions.builder().getClass())))
                .thenReturn(lowRequest);
        when(lowRequest.call())
                .thenReturn(lowResponse);
        when(lowResponse.content())
                .thenReturn("0.1");

        GroqMlPromptGuard guard = new GroqMlPromptGuard(
                chatClient,
                fallback,
                "meta-llama/llama-prompt-guard-2-86m",
                0.5
        );

        assertThat(guard.evaluate("high risk").suspicious()).isTrue();
        assertThat(guard.evaluate("low risk").suspicious()).isFalse();
    }
}