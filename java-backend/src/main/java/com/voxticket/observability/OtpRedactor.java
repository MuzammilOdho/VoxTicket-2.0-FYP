package com.voxticket.observability;

import java.util.regex.Pattern;

/**
 * P3.2: masks OTP-like digit spans at the audit/trace boundary.
 *
 * <p>Verification-turn OTPs are already parsed and redacted before they
 * reach history (see {@code SensitiveTurnParser}), but a code can still
 * leak through other paths: a tool result echoing digits, a detail string,
 * or a transcript recorded outside a verification turn. This is the last
 * line of defense - every persisted message, audit event detail, and trace
 * decision value passes through {@link #mask(String)}.
 *
 * <p>A span is masked when it is a standalone run of 4-8 ASCII digits: not
 * part of a longer digit run, and not adjacent to letters or {@code '-'}
 * (so {@code ORD-123456}, {@code TCK-123456}, {@code ABC123456}, phone
 * numbers and 9+ digit runs survive). The replacement reuses the
 * established verification-code placeholder so traces keep one vocabulary.
 *
 * <p>Known trade-off: bare 4-8 digit amounts ("1500") are indistinguishable
 * from short codes and are masked too. Traces record flow, not exact
 * amounts, so this is accepted - the alternative is leaking real OTPs.
 */
public final class OtpRedactor {

    /** Standalone 4-8 ASCII digits, not embedded in identifiers or longer runs. */
    private static final Pattern OTP_LIKE = Pattern.compile("(?<![\\p{Alnum}\\-])\\d{4,8}(?!\\d)");

    /** The established placeholder for a redacted verification code. */
    public static final String MASK = "[verification code provided]";

    private OtpRedactor() {
    }

    /**
     * Returns the text with every OTP-like digit span replaced by
     * {@link #MASK}. Null-safe (null in, null out). Pure and side-effect
     * free, so it is safe to call on already-masked text.
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        return OTP_LIKE.matcher(text).replaceAll(MASK);
    }
}
