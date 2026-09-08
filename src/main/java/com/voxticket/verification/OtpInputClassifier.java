package com.voxticket.verification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OtpInputClassifier {

    // FIX: \b\d{6}\b matched digits embedded in hyphenated references (e.g. "ORD-123456"),
    // because \b only cares about word-vs-non-word transitions, and '-' is non-word - so the
    // boundary right after the hyphen looked identical to a boundary around a standalone code.
    // This instead requires nothing word-like OR hyphen immediately before, and no digit
    // immediately after.
    private static final Pattern SIX_CONSECUTIVE_DIGITS = Pattern.compile("(?<![\\w-])\\d{6}(?!\\d)");
    private static final Pattern RESEND_KEYWORDS = Pattern.compile(
            "\\b(resend|send (it )?again|new code|didn'?t (get|receive)|haven'?t (got|received))\\b", Pattern.CASE_INSENSITIVE);

    public OtpInputResult classify(String message) {
        String text = message == null ? "" : message.trim();

        Matcher consecutive = SIX_CONSECUTIVE_DIGITS.matcher(text);
        if (consecutive.find()) {
            return OtpInputResult.code(consecutive.group());
        }

        if (text.replaceAll("[0-9\\s]", "").isEmpty()) {
            String digitsOnly = text.replaceAll("\\s", "");
            if (digitsOnly.length() == 6) {
                return OtpInputResult.code(digitsOnly);
            }
        }

        if (RESEND_KEYWORDS.matcher(text).find()) {
            return OtpInputResult.resendRequested();
        }

        return OtpInputResult.other();
    }
}