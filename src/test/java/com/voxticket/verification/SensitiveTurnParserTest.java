package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveTurnParserTest {

    private final SensitiveTurnParser parser = new SensitiveTurnParser();

    @Test
    void plainOtpProducesSingleCandidateWithNoResidual() {
        SensitiveTurn turn = parser.parse("482916");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.multipleCandidates()).isFalse();
        assertThat(turn.resendRequested()).isFalse();
        assertThat(turn.residualText()).isNull();
        assertThat(turn.hasOtpCandidate()).isTrue();
        assertThat(turn.redactedText()).isEqualTo("[verification code provided]");
        assertThat(turn.redactedText()).doesNotContain("482916");
    }

    @Test
    void otpWithWhitespaceBecomesOneCandidate() {
        SensitiveTurn turn = parser.parse("482 916");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.redactedText()).isEqualTo("[verification code provided]");
    }

    @Test
    void repeatedIdenticalCodesAreNotAmbiguousAndBothAreRedacted() {
        SensitiveTurn turn = parser.parse("482916 and 482916");

        assertThat(turn.multipleCandidates()).isFalse();
        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.redactedText())
                .isEqualTo("[verification code provided] and [verification code provided]");
        assertThat(turn.redactedText()).doesNotContain("482916");
        assertThat(turn.residualText()).isNull();
    }

    @Test
    void spacedAndUnspacedFormsOfTheSameCodeAreNotAmbiguousAndBothAreRedacted() {
        SensitiveTurn turn = parser.parse("482916 or 482 916");

        assertThat(turn.multipleCandidates()).isFalse();
        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.redactedText()).doesNotContain("482916").doesNotContain("482 916");
        assertThat(turn.residualText()).isNull();
    }

    @Test
    void arabicIndicDigitsNormalizeToAsciiCandidate() {
        // Arabic-Indic digits ٤٨٢٩١٦ == 482916
        SensitiveTurn turn = parser.parse("٤٨٢٩١٦");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.redactedText()).isEqualTo("[verification code provided]");
        assertThat(turn.redactedText()).doesNotContain("٤٨٢٩١٦");
    }

    @Test
    void easternArabicDigitsWithProse() {
        // Eastern Arabic (Farsi/Urdu) digits ۱۲۳۴۵۶ == 123456
        SensitiveTurn turn = parser.parse("mera code ۱۲۳۴۵۶ hai");

        assertThat(turn.otpCandidate()).isEqualTo("123456");
    }

    @Test
    void otpWithProseKeepsNoResidualWhenOnlyIntroRemains() {
        SensitiveTurn turn = parser.parse("my code is 482916");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.residualText()).isNull();
        assertThat(turn.hasResidual()).isFalse();
    }

    @Test
    void otpPlusResidualExtractsBoth() {
        SensitiveTurn turn = parser.parse("482916 and where is ORD-10002?");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.residualText()).isEqualTo("where is ORD-10002?");
        assertThat(turn.redactedText()).isEqualTo("[verification code provided] and where is ORD-10002?");
    }

    @Test
    void otpPlusMutationResidualKeepsTheMutationRequest() {
        SensitiveTurn turn = parser.parse("482916 and cancel ORD-10002");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.residualText()).isEqualTo("cancel ORD-10002");
    }

    @Test
    void residualCleanupHandlesCommaAndIntroPhrase() {
        SensitiveTurn turn = parser.parse("code is 482916, also where is ORD-10002?");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.residualText()).isEqualTo("where is ORD-10002?");
    }

    @Test
    void identifierEmbeddedDigitsAreNeverCandidates() {
        assertThat(parser.parse("ORD-123456").otpCandidate()).isNull();
        assertThat(parser.parse("TCK-123456").otpCandidate()).isNull();
        assertThat(parser.parse("CLM-123456").otpCandidate()).isNull();
        assertThat(parser.parse("RET-123456").otpCandidate()).isNull();
        assertThat(parser.parse("SKU-123456").otpCandidate()).isNull();
        assertThat(parser.parse("ABC123456").otpCandidate()).isNull();
        assertThat(parser.parse("where is ORD-123456").otpCandidate()).isNull();
    }

    @Test
    void sevenDigitRunsAreNeverCandidates() {
        assertThat(parser.parse("1234567").otpCandidate()).isNull();
        assertThat(parser.parse("call 12345678").otpCandidate()).isNull();
    }

    @Test
    void multipleDistinctCandidatesAreAmbiguousAndSubmitNothing() {
        SensitiveTurn turn = parser.parse("482916 or 123456");

        assertThat(turn.multipleCandidates()).isTrue();
        assertThat(turn.otpCandidate()).isNull();
        assertThat(turn.hasOtpCandidate()).isFalse();
        assertThat(turn.resendRequested()).isFalse();
        assertThat(turn.redactedText()).doesNotContain("482916").doesNotContain("123456");
        assertThat(turn.redactedText()).contains("[verification code provided]");
    }

    @Test
    void repeatedSameCodeIsNotAmbiguous() {
        SensitiveTurn turn = parser.parse("482916, I said 482916");

        assertThat(turn.multipleCandidates()).isFalse();
        assertThat(turn.otpCandidate()).isEqualTo("482916");
    }

    @Test
    void resendOnlyIsDetected() {
        SensitiveTurn turn = parser.parse("please resend the code");

        assertThat(turn.resendRequested()).isTrue();
        assertThat(turn.otpCandidate()).isNull();
    }

    @Test
    void resendVariantsAreDetected() {
        assertThat(parser.parse("send again please").resendRequested()).isTrue();
        assertThat(parser.parse("I need a new code").resendRequested()).isTrue();
        assertThat(parser.parse("didn't receive it").resendRequested()).isTrue();
        assertThat(parser.parse("haven't got the code").resendRequested()).isTrue();
    }

    @Test
    void explicitOtpCandidateWinsOverResendLanguage() {
        SensitiveTurn turn = parser.parse("resend 482916");

        assertThat(turn.otpCandidate()).isEqualTo("482916");
        assertThat(turn.resendRequested()).isFalse();
    }

    @Test
    void resendPlusReadResidualKeepsTheQuestion() {
        SensitiveTurn turn = parser.parse("resend the code and where is ORD-10002?");

        assertThat(turn.resendRequested()).isTrue();
        assertThat(turn.otpCandidate()).isNull();
        assertThat(turn.residualText()).isEqualTo("where is ORD-10002?");
        // No OTP was present, so nothing needs redacting.
        assertThat(turn.redactedText()).isEqualTo("resend the code and where is ORD-10002?");
    }

    @Test
    void plainTextHasNoOtpNoResendNoResidual() {
        SensitiveTurn turn = parser.parse("where is my order?");

        assertThat(turn.otpCandidate()).isNull();
        assertThat(turn.resendRequested()).isFalse();
        assertThat(turn.residualText()).isNull();
        assertThat(turn.redactedText()).isEqualTo("where is my order?");
    }

    @Test
    void punctuationAndWhitespaceOnlyInputIsSafe() {
        SensitiveTurn turn = parser.parse("  ... !!!  ");

        assertThat(turn.otpCandidate()).isNull();
        assertThat(turn.resendRequested()).isFalse();
        assertThat(turn.residualText()).isNull();
    }

    @Test
    void nullInputIsSafe() {
        SensitiveTurn turn = parser.parse(null);

        assertThat(turn.otpCandidate()).isNull();
        assertThat(turn.resendRequested()).isFalse();
        assertThat(turn.residualText()).isNull();
        assertThat(turn.redactedText()).isEmpty();
    }

    @Test
    void mixedTurnRedactsOtpButKeepsResidualForHistory() {
        SensitiveTurn turn = parser.parse("482916 and check my other order");

        assertThat(turn.redactedText()).isEqualTo("[verification code provided] and check my other order");
        assertThat(turn.residualText()).isEqualTo("check my other order");
        assertThat(turn.redactedText()).doesNotContain("482916");
    }
}
