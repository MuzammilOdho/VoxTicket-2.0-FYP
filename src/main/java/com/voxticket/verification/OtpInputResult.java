package com.voxticket.verification;

public record OtpInputResult(Type type, String code) {

    public enum Type { CODE, RESEND_REQUESTED, OTHER }

    public static OtpInputResult code(String code) {
        return new OtpInputResult(Type.CODE, code);
    }

    public static OtpInputResult resendRequested() {
        return new OtpInputResult(Type.RESEND_REQUESTED, null);
    }

    public static OtpInputResult other() {
        return new OtpInputResult(Type.OTHER, null);
    }
}