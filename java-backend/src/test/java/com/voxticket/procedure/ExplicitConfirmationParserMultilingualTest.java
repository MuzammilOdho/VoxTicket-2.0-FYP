package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pass 2C: the whole-message-only confirmation classifier recognizes a
 * narrow set of standalone Roman-Urdu and Urdu-script yes/no tokens while
 * keeping compound, corrective, and ambiguous messages UNCLEAR.
 */
class ExplicitConfirmationParserMultilingualTest {

    private final ExplicitConfirmationParser classifier = new ExplicitConfirmationParser();

    @ParameterizedTest
    @ValueSource(strings = {"haan", "han", "ha", "ji", "jee", "haan please", "yes please", "jee haan", "ji haan", "ہاں", "جی", "جی ہاں", "Haan", "  haan  ", "Ha", "JI"})
    void standaloneRomanUrduAndUrduScriptYesTokensClassifyAsYes(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.YES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nahi", "nahin", "nahi karo", "jee nahi", "ji nahi", "نہیں", "Nahin", "  nahi "})
    void standaloneRomanUrduAndUrduScriptNoTokensClassifyAsNo(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.NO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "haan, file kar do",          // compound: confirmation + new request
            "haan aur mera order kahan hai", // compound: confirmation + status question
            "nahi, ORD-10002 wala",       // corrective: no to this order, not a decline
            "haan likin pehle batao",     // conditional: confirmation with a caveat
            "mera haan matlab tha",       // narrative mention, not a standalone answer
            "shayad haan",                // hedged, not an unambiguous yes
            "yes and cancel ORD-10002",   // compound: approval + new mutation
            "haan but ORD-10002 wala",    // compound with correction target
            "yes, but use the other item", // conditional approval
            "no, I meant ORD-10002",      // corrective: not a decline of this action
            "nahi doosra item tha",       // corrective in Roman Urdu
            "what happens if I say yes?", // question about confirming
            "can I confirm later?",       // deferral question
            "nahi karo abhi",             // extra word beyond the standalone token
    })
    void compoundCorrectiveOrHedgedMessagesStayUnclear(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes!:YES", "haan.:YES", "جی!:YES", "sure.:YES",
            "no!:NO", "nahi.:NO", "نہیں!:NO"})
    void punctuationOnlyVariantsOfStandaloneConfirmationsAreSafe(String messageAndExpected) {
        String[] parts = messageAndExpected.split(":");
        assertThat(classifier.classify(parts[0]))
                .isEqualTo(ConfirmationDecision.valueOf(parts[1]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes?", "haan?", "ha?", "ji?", "confirm?", "ہاں؟", "جی؟",
            "no?", "nahi?", "نہیں؟", "haan?!"})
    void questionFormsNeverAuthorize(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wait", "na", "نہ", "ok", "okay", "okay.", "Wait", "NA"})
    void weakOrAmbiguousTokensDoNotResolveTheGuardedProcedure(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sure", "Sure", "sure!"})
    void standaloneSureRemainsAnExplicitAffirmative(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.YES);
    }

    @Test
    void englishBehaviorIsUnchanged() {
        assertThat(classifier.classify("yes")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("no")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("yes, and where is my other order?")).isEqualTo(ConfirmationDecision.UNCLEAR);
    }
}
