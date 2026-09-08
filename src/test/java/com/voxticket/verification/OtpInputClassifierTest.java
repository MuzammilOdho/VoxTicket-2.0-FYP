package com.voxticket.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OtpInputClassifierTest {

    private final OtpInputClassifier classifier = new OtpInputClassifier();

    @Test
    void plainSixDigitCodeIsRecognized() {
        var result = classifier.classify("123456");
        assertThat(result.type()).isEqualTo(OtpInputResult.Type.CODE);
        assertThat(result.code()).isEqualTo("123456");
    }

    @Test
    void codeWithSurroundingWordsIsRecognized() {
        var result = classifier.classify("it's 654321 I think");
        assertThat(result.type()).isEqualTo(OtpInputResult.Type.CODE);
        assertThat(result.code()).isEqualTo("654321");
    }

    @Test
    void spacedOutDigitsAreRecognizedOnlyWhenTheWholeMessageIsJustDigits() {
        var result = classifier.classify("1 2 3 4 5 6");
        assertThat(result.type()).isEqualTo(OtpInputResult.Type.CODE);
        assertThat(result.code()).isEqualTo("123456");
    }

    @Test
    void aSixDigitSequenceInsideAnUnrelatedSentenceIsNotMisreadWhenNotBounded() {
        // ORD-123456 - the digits are attached to other characters, not a standalone token
        var result = classifier.classify("my order is ORD-123456");
        assertThat(result.type()).isEqualTo(OtpInputResult.Type.OTHER);
    }

    @Test
    void resendKeywordsAreRecognized() {
        assertThat(classifier.classify("I didn't get it").type()).isEqualTo(OtpInputResult.Type.RESEND_REQUESTED);
        assertThat(classifier.classify("can you resend that").type()).isEqualTo(OtpInputResult.Type.RESEND_REQUESTED);
        assertThat(classifier.classify("send me a new code").type()).isEqualTo(OtpInputResult.Type.RESEND_REQUESTED);
    }

    @Test
    void unrelatedQuestionsAreOther() {
        assertThat(classifier.classify("how long will this take?").type()).isEqualTo(OtpInputResult.Type.OTHER);
    }
}