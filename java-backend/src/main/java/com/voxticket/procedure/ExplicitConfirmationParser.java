package com.voxticket.procedure;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pass 2D-A: explicit standalone-confirmation parser. This is deterministic
 * authorization, not general multilingual intent detection: only a message
 * whose semantic content is solely approval or solely rejection - the ENTIRE
 * message, once trimmed and stripped of trailing punctuation - may advance
 * the pending procedure. Anything else, including compound ("yes and cancel
 * my other order"), corrective ("no, I meant ORD-10002"), hedged, or
 * question forms ("what happens if I say yes?"), returns UNCLEAR.
 *
 * <p>It is only ever consulted while a procedure is {@code AWAITING_CONFIRMATION}.
 * UNCLEAR input reaches SupportAgent in read-only guarded mode (no mutation
 * tools), so a missed or compound confirmation can never start a second
 * procedure - defense in depth for the live duplicate-claim bug, where an
 * unrecognized "ha" led the model to call requestClaim again.
 *
 * <p>The token sets are intentionally bounded. Whole-message matching keeps
 * compound messages containing these tokens ("haan, file kar do") UNCLEAR.
 *
 * <p>Pass 2D cleanup decisions, all on the side of strict authorization:
 * question forms ({@code ?} and {@code ؟}) are always UNCLEAR - a question
 * never authorizes, on either the YES or the NO side. {@code "wait"},
 * {@code "na"}, and {@code "نہ"} were removed from NO as too ambiguous for
 * an authorization boundary ("wait" means pause/hold, not decline).
 * {@code "ok"}/{@code "okay"} were removed from YES as acknowledgments
 * rather than explicit approval; standalone {@code "sure"} is retained as an
 * explicit affirmative answer to a yes/no question.
 */
@Component
public class ExplicitConfirmationParser {

    private static final Set<String> YES_PHRASES = Set.of(
            "yes", "yeah", "yep", "yup", "sure", "confirm", "confirmed", "go ahead", "please do", "do it",
            "yes please",
            // Standalone Roman-Urdu / Urdu-script yes tokens (whole-message only).
            "haan", "han", "ha", "ji", "jee", "haan please", "jee haan", "ji haan", "ہاں", "جی", "جی ہاں");
    private static final Set<String> NO_PHRASES = Set.of(
            "no", "nope", "don't", "do not", "stop", "never mind", "nevermind", "cancel that",
            // Standalone Roman-Urdu / Urdu-script no tokens (whole-message only).
            "nahi", "nahin", "nahi karo", "jee nahi", "ji nahi", "نہیں");

    public ConfirmationDecision classify(String message) {
        if (message == null || message.isBlank()) {
            return ConfirmationDecision.UNCLEAR;
        }
        String trimmed = message.trim();
        // Pass 2D cleanup: a question never authorizes. Both the ASCII and
        // the Arabic question mark force UNCLEAR before any punctuation
        // normalization runs - this applies to the NO side as well, since
        // declining also resolves the guarded procedure.
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '?' || last == '؟') {
            return ConfirmationDecision.UNCLEAR;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        if (normalized.isEmpty()) {
            return ConfirmationDecision.UNCLEAR;
        }
        if (YES_PHRASES.contains(normalized)) {
            return ConfirmationDecision.YES;
        }
        if (NO_PHRASES.contains(normalized)) {
            return ConfirmationDecision.NO;
        }
        return ConfirmationDecision.UNCLEAR;
    }
}