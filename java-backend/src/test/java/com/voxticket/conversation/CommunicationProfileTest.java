package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Phase 5: the injected communication profile is deterministic static
 * guidance keyed on the resolved {@link ConversationLanguage} - the model
 * follows it instead of guessing the customer's language.
 */
class CommunicationProfileTest {

    @Test
    void everyLanguageRendersAStableCodeAndWritingGuidance() {
        for (ConversationLanguage language : ConversationLanguage.values()) {
            String rendered = CommunicationProfile.render(language);

            assertThat(rendered).startsWith("COMMUNICATION PROFILE\ncustomerLanguage: " + language.name() + "\n");
            assertThat(rendered).contains("no markdown");
        }
    }

    @Test
    void englishProfileAsksForNaturalSupportEnglish() {
        String rendered = CommunicationProfile.render(ConversationLanguage.ENGLISH);

        assertThat(rendered).contains("natural English");
    }

    @Test
    void urduProfileAsksForUrduScriptAndPoliteRegister() {
        String rendered = CommunicationProfile.render(ConversationLanguage.URDU);

        assertThat(rendered).contains("Urdu script").contains("aap");
    }

    @Test
    void romanUrduProfileAsksForLatinScript() {
        String rendered = CommunicationProfile.render(ConversationLanguage.ROMAN_URDU);

        assertThat(rendered).contains("Roman Urdu").contains("Latin script");
    }

    @Test
    void codeSwitchProfileNamesTheMixingRule() {
        String rendered = CommunicationProfile.render(ConversationLanguage.CODE_SWITCH);

        assertThat(rendered).contains("Roman-Urdu").contains("English");
    }

    @Test
    void nullLanguageDefaultsToEnglish() {
        String rendered = CommunicationProfile.render(null);

        assertThat(rendered).contains("customerLanguage: ENGLISH");
    }

    @Test
    void profilesCarryNoUserTextOrInternalState() {
        for (ConversationLanguage language : ConversationLanguage.values()) {
            String rendered = CommunicationProfile.render(language);

            assertThat(rendered).doesNotContain("ORD-").doesNotContain("SKU-");
        }
    }
}
