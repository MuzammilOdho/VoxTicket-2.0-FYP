package com.voxticket.verification;

/**
 * Pass 2D-A: the deterministic interpretation of one user turn while a
 * procedure is {@code AWAITING_VERIFICATION}.
 *
 * <p>Replaces the old binary OTP/normal-text classifier: a turn can carry a
 * verification code <em>and</em> a residual request ("482916 and check my
 * other order"), and that residual must not be silently dropped. The parser
 * is pure - no DB, no model, no session mutation - and is only consulted when
 * a verification is actually pending, never as general six-digit detection.
 *
 * @param otpCandidate      the single plausible six-digit code, or {@code null}
 *                          when there is none or when several distinct
 *                          candidates make guessing unsafe ({@link #multipleCandidates()})
 * @param multipleCandidates {@code true} when the turn contains two or more
 *                          <em>distinct</em> plausible codes; the runtime must
 *                          ask for one code and submit nothing
 * @param resendRequested   {@code true} when no OTP candidate is present and
 *                          the text carries resend language
 * @param residualText      the turn with OTP/resend spans removed and
 *                          connector noise cleaned, or {@code null} when no
 *                          substantive residual content remains
 * @param redactedText      the turn with every OTP candidate span replaced by
 *                          the stable {@code [verification code provided]}
 *                          placeholder; this is the only form that may enter
 *                          conversation history, audit, or model input
 */
public record SensitiveTurn(
        String otpCandidate,
        boolean multipleCandidates,
        boolean resendRequested,
        String residualText,
        String redactedText) {

    public boolean hasOtpCandidate() {
        return otpCandidate != null && !multipleCandidates;
    }

    public boolean hasResidual() {
        return residualText != null && !residualText.isBlank();
    }

    public static SensitiveTurn empty(String originalText) {
        String text = originalText == null ? "" : originalText;
        return new SensitiveTurn(null, false, false, null, text);
    }
}
