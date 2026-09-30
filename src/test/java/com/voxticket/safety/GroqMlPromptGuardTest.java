package com.voxticket.safety;

import com.voxticket.observability.TurnMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroqMlPromptGuardTest {

    private final PromptGuard fallback = mock(PromptGuard.class);
    private final PromptGuardProperties properties = new PromptGuardProperties("ml", "meta-llama/llama-prompt-guard-2-86m", 0.5, 10);
    private final TurnMetrics turnMetrics = mock(TurnMetrics.class);

    @Test
    void blankInputIsAllowedWithoutCallingTheModel() {
        ChatClient chatClient = mock(ChatClient.class);
        GroqMlPromptGuard guard = new GroqMlPromptGuard(chatClient, fallback, properties, turnMetrics);

        assertThat(guard.evaluate("").suspicious()).isFalse();
        verifyNoInteractions(chatClient);
    }

    @Test
    void fallsBackToHeuristicWhenTheModelCallThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("network error"));
        when(fallback.evaluate("test message")).thenReturn(PromptGuardVerdict.allow());
        GroqMlPromptGuard guard = new GroqMlPromptGuard(chatClient, fallback, properties, turnMetrics);

        assertThat(guard.evaluate("test message").suspicious()).isFalse();
        verify(fallback).evaluate("test message");
    }

    @Test
    void parseScoreAcceptsValidScoresInRange() {
        assertThat(GroqMlPromptGuard.parseScore("0.87")).isEqualTo(0.87);
        assertThat(GroqMlPromptGuard.parseScore("0")).isEqualTo(0.0);
        assertThat(GroqMlPromptGuard.parseScore("1")).isEqualTo(1.0);
    }

    @Test
    void parseScoreRejectsOutOfRangeOrUnparseableValues() {
        assertThat(GroqMlPromptGuard.parseScore("1.5")).isNull();
        assertThat(GroqMlPromptGuard.parseScore("-0.1")).isNull();
        assertThat(GroqMlPromptGuard.parseScore(null)).isNull();
        assertThat(GroqMlPromptGuard.parseScore("")).isNull();
        assertThat(GroqMlPromptGuard.parseScore("benign")).isNull();
    }

    @Test
    void doesNotSetPerRequestOptions() {
        // Regression: the guard ChatClient already defaults to the classifier
        // model with maxCompletionTokens set. Adding per-request options with
        // maxTokens made Spring AI send both max_tokens and
        // max_completion_tokens, which Groq rejects with 400 Bad Request.
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user("hi").call().content()).thenReturn("0.1");
        GroqMlPromptGuard guard = new GroqMlPromptGuard(client, fallback, properties, turnMetrics);

        assertThat(guard.evaluate("hi").suspicious()).isFalse();
        verify(client.prompt(), never()).options(any(ChatOptions.Builder.class));
    }

    @Test
    void aDifferentConfiguredThresholdChangesTheOutcomeWithoutCodeChanges() {
        // A score of 0.6 is malicious against the default 0.5 threshold but benign against 0.7.
        ChatClient lenientClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(lenientClient.prompt().user("test").call().content()).thenReturn("0.6");
        PromptGuardProperties lenientProperties = new PromptGuardProperties("ml", "meta-llama/llama-prompt-guard-2-86m", 0.7, 10);

        GroqMlPromptGuard guard = new GroqMlPromptGuard(lenientClient, fallback, lenientProperties, turnMetrics);

        assertThat(guard.evaluate("test").suspicious()).isFalse();
    }
}
