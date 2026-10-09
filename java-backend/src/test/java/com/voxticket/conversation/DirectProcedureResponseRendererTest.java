package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.procedure.ProcedureOutcome;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pass 2C (Task 20): the direct-response renderer keys only on the outcome
 * code and safe structured metadata - never on the English message prose.
 */
class DirectProcedureResponseRendererTest {

    private final DirectProcedureResponseRenderer renderer = new DirectProcedureResponseRenderer();

    private static ProcedureOutcome outcome(String code, Map<String, String> metadata) {
        // Deliberately misleading English prose: the renderer must ignore it.
        return new ProcedureOutcome(false, code, "This message is a lie about what happened.", metadata);
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void verificationRequiredNamesTheMaskedDestinationWithoutOtp(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("VERIFICATION_REQUIRED",
                Map.of("verificationIssue", "CHALLENGE_ISSUED", "maskedDestination", "********4567")));

        assertThat(rendered).contains("********4567");
        assertThat(rendered).doesNotContain("This message is a lie");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void ambiguousOtpAsksForExactlyOneCode(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("OTP_AMBIGUOUS", Map.of()));

        assertThat(rendered).doesNotContain("This message is a lie");
        assertThat(rendered).isNotBlank();
    }

    @Test
    void ambiguousOtpTextDiffersAcrossLanguages() {
        String english = renderer.render(ConversationLanguage.ENGLISH, outcome("OTP_AMBIGUOUS", Map.of()));
        String urdu = renderer.render(ConversationLanguage.URDU, outcome("OTP_AMBIGUOUS", Map.of()));

        assertThat(english).contains("6-digit verification code");
        assertThat(urdu).contains("6");
        assertThat(english).isNotEqualTo(urdu);
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void wrongCodeIsDistinctFromExpired(ConversationLanguage language) {
        String wrong = renderer.render(language,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "WRONG_CODE")));
        String expired = renderer.render(language,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "EXPIRED")));

        assertThat(wrong).isNotEqualTo(expired);
        assertThat(wrong).doesNotContain("This message is a lie");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void maxAttemptsExceededIsDistinctFromWrongCode(ConversationLanguage language) {
        String maxAttempts = renderer.render(language,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "MAX_ATTEMPTS_EXCEEDED")));
        String wrong = renderer.render(language,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "WRONG_CODE")));

        assertThat(maxAttempts).isNotEqualTo(wrong);
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void rateLimitedResendCooldownIsDistinctFromAttemptLimits(ConversationLanguage language) {
        String cooldown = renderer.render(language,
                outcome("VERIFICATION_RATE_LIMITED", Map.of("verificationIssue", "RESEND_COOLDOWN")));
        String limited = renderer.render(language,
                outcome("VERIFICATION_RATE_LIMITED", Map.of("verificationIssue", "SESSION_RATE_LIMITED")));

        assertThat(cooldown).isNotEqualTo(limited);
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void cancellationPreservesThePaymentConsequence(ConversationLanguage language) {
        String noRefund = renderer.render(language,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "NO_REFUND_REQUIRED")));
        String refundRequired = renderer.render(language,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "REFUND_REQUIRED")));
        String manualReview = renderer.render(language,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "MANUAL_REVIEW_REQUIRED")));
        String voidAuth = renderer.render(language,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "VOID_AUTHORIZATION")));

        assertThat(noRefund).contains("ORD-10001");
        assertThat(refundRequired).contains("ORD-10001");
        assertThat(manualReview).contains("ORD-10001");
        assertThat(voidAuth).contains("ORD-10001");
        assertThat(noRefund).isNotEqualTo(refundRequired);
        assertThat(noRefund).isNotEqualTo(manualReview);
        assertThat(noRefund).isNotEqualTo(voidAuth);
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void cancellationNeverInventsRefundTiming(ConversationLanguage language) {
        String rendered = renderer.render(language,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "REFUND_REQUIRED")));

        assertThat(rendered.toLowerCase()).doesNotContain("3-5 days", "3–5 days", "days");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void returnStartedCarriesTheReturnNumber(ConversationLanguage language) {
        String rendered = renderer.render(language,
                outcome("RETURN_STARTED", Map.of("orderReference", "ORD-10001", "returnNumber", "RET-00077")));

        assertThat(rendered).contains("RET-00077").contains("ORD-10001");
        assertThat(rendered).doesNotContain("This message is a lie");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void claimFiledCarriesTheClaimNumber(ConversationLanguage language) {
        String rendered = renderer.render(language,
                outcome("CLAIM_FILED", Map.of("orderReference", "ORD-10001", "claimNumber", "CLM-00042")));

        assertThat(rendered).contains("CLM-00042").contains("ORD-10001");
        assertThat(rendered).doesNotContain("This message is a lie");
    }

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void unknownCodeFallsBackToALocalizedSafeMessage(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("SOME_FUTURE_CODE", Map.of()));

        assertThat(rendered).isNotBlank();
        assertThat(rendered).doesNotContain("This message is a lie");
        assertThat(rendered).doesNotContain("SOME_FUTURE_CODE");
    }

    @Test
    void devOtpNeverAppearsInRenderedText() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("verificationIssue", "CHALLENGE_ISSUED");
        metadata.put("maskedDestination", "********4567");
        metadata.put("devOtp", "482916");

        for (ConversationLanguage language : ConversationLanguage.values()) {
            assertThat(renderer.render(language, outcome("VERIFICATION_REQUIRED", metadata)))
                    .doesNotContain("482916")
                    .contains("********4567");
        }
    }

    @Test
    void englishTemplatesAreSpeechFriendlyPlainText() {
        String rendered = renderer.render(ConversationLanguage.ENGLISH,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "WRONG_CODE")));

        assertThat(rendered).doesNotContain("#", "*", "|", "```");
        assertThat(rendered).doesNotContain("😀");
        assertThat(rendered.split("\\. ").length).isLessThanOrEqualTo(2);
    }

    @Test
    void urduTemplatesUseUrduScriptAndKeepIdentifiers() {
        String rendered = renderer.render(ConversationLanguage.URDU,
                outcome("CANCELLED", Map.of("orderReference", "ORD-10001", "paymentConsequence", "NO_REFUND_REQUIRED")));

        assertThat(rendered).contains("ORD-10001");
        assertThat(rendered.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF)).isTrue();
    }

    @Test
    void romanUrduTemplatesUseTheTaskExampleStyle() {
        String rendered = renderer.render(ConversationLanguage.ROMAN_URDU,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "WRONG_CODE")));

        assertThat(rendered).contains("Yeh code match nahi hua");
    }

    @Test
    void codeSwitchTemplatesUseTheTaskExampleStyle() {
        String rendered = renderer.render(ConversationLanguage.CODE_SWITCH,
                outcome("VERIFICATION_FAILED", Map.of("verificationReason", "WRONG_CODE")));

        assertThat(rendered).contains("Code match nahi hua");
    }

    @Test
    void nullOutcomeRendersASafeFallbackInsteadOfThrowing() {
        for (ConversationLanguage language : ConversationLanguage.values()) {
            assertThat(renderer.render(language, null)).isNotBlank();
        }
        assertThat(renderer.render(null, outcome("CANCELLED", Map.of()))).isNotBlank();
    }

    // Phase 1C: ORDER_FULFILLED denies cancellation for PARTIALLY_FULFILLED
    // as well as FULFILLED orders, so no language may state "delivered".

    @ParameterizedTest
    @EnumSource(ConversationLanguage.class)
    void orderFulfilledDenialNeverClaimsDelivered(ConversationLanguage language) {
        String rendered = renderer.render(language, outcome("NOT_ELIGIBLE",
                Map.of("orderReference", "ORD-10001", "denialReason", "ORDER_FULFILLED",
                        "paymentConsequence", "NO_REFUND_REQUIRED")));

        assertThat(rendered).doesNotContain("Something went wrong");
        assertThat(rendered.toLowerCase()).doesNotContain("deliver");
        assertThat(rendered).doesNotContain("ڈیلیور");
    }

    @Test
    void orderFulfilledDenialWordingPerLanguage() {
        Map<ConversationLanguage, String> expected = Map.of(
                ConversationLanguage.ENGLISH, "It has already been fulfilled.",
                ConversationLanguage.URDU, "یہ پہلے ہی بھیج دیا گیا ہے۔",
                ConversationLanguage.ROMAN_URDU, "Yeh pehle hi bhej diya gaya hai.",
                ConversationLanguage.CODE_SWITCH, "Yeh already dispatch ho chuka hai.");
        for (var entry : expected.entrySet()) {
            String rendered = renderer.render(entry.getKey(), outcome("NOT_ELIGIBLE",
                    Map.of("orderReference", "ORD-10001", "denialReason", "ORDER_FULFILLED",
                            "paymentConsequence", "NO_REFUND_REQUIRED")));
            assertThat(rendered)
                    .as("ORDER_FULFILLED in %s", entry.getKey())
                    .contains(entry.getValue());
        }
    }
}
