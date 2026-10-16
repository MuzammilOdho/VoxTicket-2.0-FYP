package com.voxticket.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P3.2: OTP-like digit spans are masked at the audit/trace boundary, while
 * order numbers, ticket numbers, and long identifiers survive untouched.
 */
class OtpRedactorTest {

    @Test
    void masksStandaloneSixDigitCode() {
        assertThat(OtpRedactor.mask("my code is 482916 please"))
                .isEqualTo("my code is [verification code provided] please");
    }

    @Test
    void masksFourToEightDigitSpans() {
        assertThat(OtpRedactor.mask("code 1234")).contains("[verification code provided]");
        assertThat(OtpRedactor.mask("code 12345678")).contains("[verification code provided]");
    }

    @Test
    void leavesIdentifiersAndLongRunsAlone() {
        assertThat(OtpRedactor.mask("order ORD-123456 shipped")).isEqualTo("order ORD-123456 shipped");
        assertThat(OtpRedactor.mask("ticket TCK-123456")).isEqualTo("ticket TCK-123456");
        assertThat(OtpRedactor.mask("ref ABC123456")).isEqualTo("ref ABC123456");
        assertThat(OtpRedactor.mask("call +923001234567")).isEqualTo("call +923001234567");
        assertThat(OtpRedactor.mask("nine digits 123456789")).isEqualTo("nine digits 123456789");
    }

    @Test
    void masksToolResultEchoes() {
        assertThat(OtpRedactor.mask("submitVerificationCode result=OK code=482916"))
                .doesNotContain("482916");
    }

    @Test
    void nullSafeAndIdempotent() {
        assertThat(OtpRedactor.mask(null)).isNull();
        String once = OtpRedactor.mask("code 482916");
        assertThat(OtpRedactor.mask(once)).isEqualTo(once);
    }
}
