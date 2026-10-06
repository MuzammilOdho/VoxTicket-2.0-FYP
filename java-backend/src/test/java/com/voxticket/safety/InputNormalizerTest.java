package com.voxticket.safety;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InputNormalizerTest {

    private final InputNormalizer normalizer = new InputNormalizer();

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        NormalizationResult result = normalizer.normalize("  where is my order?  ");

        assertThat(result.accepted()).isTrue();
        assertThat(result.text()).isEqualTo("where is my order?");
    }

    @Test
    void nullInputIsAcceptedAsEmptyString() {
        NormalizationResult result = normalizer.normalize(null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void excessivelyLongInputIsRejectedNotTruncated() {
        NormalizationResult result = normalizer.normalize("a".repeat(5000));

        assertThat(result.accepted()).isFalse();
        assertThat(result.text()).isNull();
        assertThat(result.rejectedLength()).isEqualTo(5000);
    }

    @Test
    void inputAtExactlyTheLimitIsAccepted() {
        NormalizationResult result = normalizer.normalize("a".repeat(4000));

        assertThat(result.accepted()).isTrue();
        assertThat(result.text()).hasSize(4000);
    }

    @Test
    void normalLengthInputIsUnchanged() {
        String message = "Where is my order ORD-10001?";

        assertThat(normalizer.normalize(message).text()).isEqualTo(message);
    }
}