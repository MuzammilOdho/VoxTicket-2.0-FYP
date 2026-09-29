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
class ConfirmationClassifierMultilingualTest {

    private final ConfirmationClassifier classifier = new ConfirmationClassifier();

    @ParameterizedTest
    @ValueSource(strings = {"haan", "han", "jee haan", "ji haan", "ہاں", "جی ہاں", "Haan", "  haan  ", "haan?"})
    void standaloneRomanUrduAndUrduScriptYesTokensClassifyAsYes(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.YES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nahi", "nahin", "jee nahi", "ji nahi", "نہیں", "Nahin", "  nahi "})
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
    })
    void compoundCorrectiveOrHedgedMessagesStayUnclear(String message) {
        assertThat(classifier.classify(message)).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @Test
    void englishBehaviorIsUnchanged() {
        assertThat(classifier.classify("yes")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("no")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("yes, and where is my other order?")).isEqualTo(ConfirmationDecision.UNCLEAR);
    }
}
