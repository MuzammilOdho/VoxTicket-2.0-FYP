package com.voxticket.conversation;

/**
 * Phase 5: the model's communication profile for the resolved customer
 * language.
 *
 * <p>The language itself is resolved deterministically in Java by
 * {@link ConversationLanguageResolver} - the model never guesses which
 * language the customer is speaking. This class owns the one thing the model
 * does need: how to write for that language - register, script, and mixing
 * rules. The profiles are static text, cheap in tokens, and identical on
 * every turn, so the model's language behavior is configuration, not
 * inference.
 *
 * <p>Deterministic and customer-safe by construction: only the stable
 * {@link ConversationLanguage} name and static guidance cross the
 * model boundary - never raw user text, never internal state.
 */
public final class CommunicationProfile {

    private CommunicationProfile() {
    }

    /**
     * Renders the injected system-prompt block for a resolved language.
     * The first line names the language as a stable code the model can
     * key on; the following lines describe how to write for it.
     */
    public static String render(ConversationLanguage language) {
        ConversationLanguage lang = language == null ? ConversationLanguage.ENGLISH : language;
        return "COMMUNICATION PROFILE\ncustomerLanguage: " + lang.name() + "\n" + profileFor(lang);
    }

    /** Package-visible for tests: the per-language writing guidance. */
    static String profileFor(ConversationLanguage language) {
        return switch (language) {
            case ENGLISH -> """
                    Reply in clear, natural English with a warm, concise support tone. \
                    Plain sentences that work when read aloud, no markdown or formatting.""";
            case URDU -> """
                    Reply in natural Urdu script (اردو) with a polite register, using "aap" for the customer. \
                    Plain spoken-style sentences, no markdown or formatting.""";
            case ROMAN_URDU -> """
                    Reply in natural Roman Urdu written in Latin script, the everyday spoken style \
                    of a Pakistani support agent. Plain sentences, no markdown or formatting.""";
            case CODE_SWITCH -> """
                    Reply in the natural English/Roman-Urdu mix of Pakistani customer support: \
                    support and commerce terms (order, refund, verification code, claim) in English, \
                    the rest in Roman Urdu. Plain sentences, no markdown or formatting.""";
        };
    }
}
