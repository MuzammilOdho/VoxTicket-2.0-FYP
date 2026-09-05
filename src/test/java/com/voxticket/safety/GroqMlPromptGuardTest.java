package com.voxticket.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class GroqMlPromptGuardTest {

    private final PromptGuard fallback = mock(PromptGuard.class);

    @Test
    void blankInputIsAllowedWithoutCallingTheModel() {
        ChatClient chatClient = mock(ChatClient.class);
        GroqMlPromptGuard guard = new GroqMlPromptGuard(chatClient, fallback, "meta-llama/llama-prompt-guard-2-86m");

        assertThat(guard.evaluate("").suspicious()).isFalse();
        verifyNoInteractions(chatClient);
    }

    @Test
    void fallsBackToHeuristicWhenTheModelCallThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("network error"));
        when(fallback.evaluate("test message")).thenReturn(PromptGuardVerdict.allow());
        GroqMlPromptGuard guard = new GroqMlPromptGuard(chatClient, fallback, "meta-llama/llama-prompt-guard-2-86m");

        PromptGuardVerdict verdict = guard.evaluate("test message");

        assertThat(verdict.suspicious()).isFalse();
        verify(fallback).evaluate("test message");
    }

    @Test
    void classifyRecognizesMaliciousLabels() {
        assertThat(GroqMlPromptGuard.classify("malicious")).isTrue();
        assertThat(GroqMlPromptGuard.classify("MALICIOUS")).isTrue();
        assertThat(GroqMlPromptGuard.classify("LABEL_1")).isTrue();
    }

    @Test
    void classifyRecognizesBenignLabels() {
        assertThat(GroqMlPromptGuard.classify("benign")).isFalse();
        assertThat(GroqMlPromptGuard.classify("LABEL_0")).isFalse();
    }

    @Test
    void classifyReturnsNullForUnrecognizedOutputRatherThanAssumingBenign() {
        assertThat(GroqMlPromptGuard.classify("")).isNull();
        assertThat(GroqMlPromptGuard.classify(null)).isNull();
        assertThat(GroqMlPromptGuard.classify("some garbled unexpected output")).isNull();
    }
}