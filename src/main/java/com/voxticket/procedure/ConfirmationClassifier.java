package com.voxticket.procedure;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Spec §21 + resolved correction: deliberately strict. Only a standalone,
 * unambiguous yes/no - the ENTIRE message, once trimmed and stripped of
 * trailing punctuation - counts. Anything else, including compound ("yes,
 * and where is my other order?") or corrective ("no, I meant ORD-10002")
 * messages, returns UNCLEAR and reaches SupportAgent instead.
 *
 * <p>This is a deliberate simplification from the earlier version, which
 * tried to auto-execute compound confirmations - that silently dropped the
 * second half of the request (there was never a way to act on "also
 * complain about the delay" through the deterministic path), and
 * misclassified corrections like "no, I meant ORD-10002" as a flat decline
 * when the customer was actually still trying to proceed, just against a
 * different order. SupportAgent has the full conversation context and can
 * ask for an explicit yes/no before anything is confirmed - it still has no
 * tool that can trigger the mutation itself, so this doesn't weaken the
 * security boundary at all, only fixes what happens with ambiguous input.
 */
@Component
public class ConfirmationClassifier {

    private static final Set<String> YES_PHRASES = Set.of(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed", "go ahead", "please do", "do it");
    private static final Set<String> NO_PHRASES = Set.of(
            "no", "nope", "don't", "do not", "stop", "never mind", "nevermind", "wait", "cancel that");

    public ConfirmationDecision classify(String message) {
        String normalized = normalize(message);
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

    private String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "");
    }
}