package com.voxticket.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * P2: the turn decision trace must never carry OTP values, secrets, system
 * prompts, or reasoning. The builder has no fields for them by construction;
 * this test pins that by serializing a fully-populated trace (the exact shape
 * the audit writer persists to {@code conversation_turn_traces}) and
 * asserting the sensitive markers are absent.
 */
class TurnTraceRedactionTest {

    private static final String OTP = "482916";
    private static final String SYSTEM_PROMPT_MARKER = "You are VoxTicket, an e-commerce customer-support assistant.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializedTraceContainsNoOtpSecretsOrPrompts() throws Exception {
        // Built the way ConversationRuntime does for a verification-path
        // turn: the OTP was redacted to a placeholder before any trace field
        // was set, so only structured codes survive.
        TurnTrace trace = TurnTrace.builder("session-1", 3, "CHAT", TraceIds.newTraceId(), Instant.now())
                .endedAt(Instant.now())
                .outcome("verification")
                .normalizeMs(1.5)
                .guardMs(12.0)
                .guardSuspicious(false)
                .guardImplementation("ml")
                .guardFallback(false)
                .language("ENGLISH")
                .intent("CANCELLATION")
                .routingStrategy("HYBRID")
                .tier("TIER_1")
                .routingReason("SEMANTIC_SIMPLE")
                .semanticMargin(0.42)
                .provider("GOOGLE")
                .model("gemini-3.5-flash-lite")
                .promptTokens(120)
                .completionTokens(45)
                .llmTotalMs(850.0)
                .ragCacheHit(false)
                .addRagDoc("policy-doc-1", "cancellation", 0.82)
                .addToolCall("searchPolicy", "OK", 42.0)
                .procedureType("CANCELLATION")
                .procedureStatus("AWAITING_VERIFICATION")
                .procedureOutcomeCode("VERIFICATION_REQUIRED")
                .detail("toolAccessMode", "FULL")
                .build();

        // The writer persists scalar columns individually and JSON for the
        // structured fields; mirror that shape here. (TurnTrace uses
        // record-style accessors, which Jackson does not auto-detect as
        // getters - the production writer never serializes the whole trace.)
        java.util.Map<String, Object> jsonShape = new java.util.LinkedHashMap<>();
        jsonShape.put("sessionId", trace.sessionId());
        jsonShape.put("turnNumber", trace.turnNumber());
        jsonShape.put("channel", trace.channel());
        jsonShape.put("traceId", trace.traceId());
        jsonShape.put("outcome", trace.outcome());
        jsonShape.put("guardSuspicious", trace.guardSuspicious());
        jsonShape.put("guardCategory", trace.guardCategory());
        jsonShape.put("guardImplementation", trace.guardImplementation());
        jsonShape.put("language", trace.language());
        jsonShape.put("intent", trace.intent());
        jsonShape.put("routingStrategy", trace.routingStrategy());
        jsonShape.put("tier", trace.tier());
        jsonShape.put("routingReason", trace.routingReason());
        jsonShape.put("semanticMargin", trace.semanticMargin());
        jsonShape.put("provider", trace.provider());
        jsonShape.put("model", trace.model());
        jsonShape.put("promptTokens", trace.promptTokens());
        jsonShape.put("completionTokens", trace.completionTokens());
        jsonShape.put("ragCacheHit", trace.ragCacheHit());
        jsonShape.put("ragDocs", trace.ragDocs());
        jsonShape.put("tools", trace.tools());
        jsonShape.put("procedureType", trace.procedureType());
        jsonShape.put("procedureStatus", trace.procedureStatus());
        jsonShape.put("procedureOutcomeCode", trace.procedureOutcomeCode());
        jsonShape.put("errorCode", trace.errorCode());
        jsonShape.put("decision", trace.decision());
        String json = objectMapper.writeValueAsString(jsonShape);

        assertThat(json).doesNotContain(OTP);
        assertThat(json).doesNotContain(SYSTEM_PROMPT_MARKER);
        assertThat(json).doesNotContain("otp");
        assertThat(json).doesNotContain("secret");
        assertThat(json).doesNotContain("reasoning");
        // Structured facts survive.
        assertThat(json).contains("VERIFICATION_REQUIRED");
        assertThat(json).contains("policy-doc-1");
        assertThat(json).contains("SEMANTIC_SIMPLE");
    }

    @Test
    void builderExposesNoContentCarryingAccessors() {
        // The redaction contract is structural: these accessor names must not
        // exist on the trace. If someone adds one, this test names it.
        var accessors = Arrays.stream(TurnTrace.class.getMethods())
                .map(m -> m.getName())
                .filter(n -> !n.equals("wait") && !n.equals("hashCode") && !n.equals("toString")
                        && !n.equals("getClass") && !n.equals("equals") && !n.equals("notify")
                        && !n.equals("notifyAll"))
                .toList();
        assertThat(accessors).doesNotContain(
                "otp", "secret", "systemPrompt", "prompt", "reasoning", "chainOfThought",
                "userText", "messageText", "documentText", "content", "text");
    }

    @Test
    void unmeasuredFieldsStayNullRatherThanFabricated() {
        // A guard-blocked turn never reaches the LLM: every LLM/routing field
        // must be null, never a zero.
        TurnTrace trace = TurnTrace.builder("s", 1, "CHAT", TraceIds.newTraceId(), Instant.now())
                .endedAt(Instant.now())
                .outcome("blocked")
                .guardSuspicious(true)
                .guardCategory("PROMPT_DISCLOSURE")
                .guardImplementation("heuristic")
                .guardFallback(false)
                .build();

        assertThat(trace.llmTtftMs()).isNull();
        assertThat(trace.llmTotalMs()).isNull();
        assertThat(trace.tier()).isNull();
        assertThat(trace.provider()).isNull();
        assertThat(trace.semanticMargin()).isNull();
        assertThat(trace.promptTokens()).isNull();
        assertThat(trace.ragDocs()).isEmpty();
        assertThat(trace.tools()).isEmpty();
    }
}
