package com.voxticket.procedure;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Spec §21. Deterministic first pass - this is what actually gates a
 * mutation, not the general-purpose LLM. Handles both cases spec/the
 * resolved decisions explicitly call out:
 * - a clean compound confirmation ("Yes, cancel it and also complain about
 *   the delay" -> YES, with the rest of the sentence left for the agent to
 *   handle as a separate request on the next normal turn);
 * - a contradictory one ("yeah, right, don't cancel it" -> UNCLEAR, never
 *   silently read as YES just because it starts with an affirmative word).
 *
 * <p>Anything this can't confidently resolve returns UNCLEAR. There is
 * deliberately no LLM-based second-guess step: per the resolved decision,
 * a single uncertain classification must never authorize a mutation, so
 * UNCLEAR just means the procedure stays parked and the customer is asked
 * again - it falls through to a normal SupportAgent turn, which can ask
 * for clarification or answer a side question without touching the
 * pending action either way.
 */
@Component
public class ConfirmationClassifier {

    private static final Pattern YES_LEADING = Pattern.compile(
            "^(yes|yeah|yep|yup|sure|ok|okay|confirmed?|go ahead|please do|do it)\\b[,.!]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_LEADING = Pattern.compile(
            "^(no|nope|don'?t|do not|stop|never ?mind|wait)\\b[,.!]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTRADICTS_YES = Pattern.compile(
            "\\b(don'?t|do not|not)\\b.{0,30}\\b(cancel|return|refund|do it|go ahead|proceed)\\b", Pattern.CASE_INSENSITIVE);

    public ConfirmationDecision classify(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return ConfirmationDecision.UNCLEAR;
        }
        if (NO_LEADING.matcher(text).lookingAt()) {
            return ConfirmationDecision.NO;
        }
        if (YES_LEADING.matcher(text).lookingAt()) {
            return CONTRADICTS_YES.matcher(text).find() ? ConfirmationDecision.UNCLEAR : ConfirmationDecision.YES;
        }
        return ConfirmationDecision.UNCLEAR;
    }
}