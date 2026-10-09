package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pass 2C (Task 19): the deterministic language resolver keys only on stable
 * lexical/script signals - OTP digits, reference identifiers and
 * punctuation never move the language.
 */
class ConversationLanguageResolverTest {

    private final ConversationLanguageResolver resolver = new ConversationLanguageResolver();

    @ParameterizedTest
    @ValueSource(strings = {"Where is my order?", "Please cancel it", "refund status", "cancel my order"})
    void englishMessagesResolveToEnglish(String text) {
        assertThat(resolver.classify(text)).hasValue(ConversationLanguage.ENGLISH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"میرا آرڈر کہاں ہے؟", "یہ آرڈر کینسل کر دیں", "جی ہاں"})
    void urduScriptMessagesResolveToUrdu(String text) {
        assertThat(resolver.classify(text)).hasValue(ConversationLanguage.URDU);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mera order kahan hai", "mujhe isay cancel karna hai", "haan", "nahi", "han", "jee"})
    void romanUrduMessagesResolveToRomanUrdu(String text) {
        assertThat(resolver.classify(text)).hasValue(ConversationLanguage.ROMAN_URDU);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mera order cancel kar do please",
            "refund kab milega please",
            "یہ order cancel کر دیں",
            "haan please proceed"})
    void mixedMessagesResolveToCodeSwitch(String text) {
        assertThat(resolver.classify(text)).hasValue(ConversationLanguage.CODE_SWITCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "ORD-10001", "RET-00042", "TRK123ABC", "   ", "?!...", "yes", "no", "ok"})
    void signalFreeMessagesCarryNoLanguage(String text) {
        assertThat(resolver.classify(text)).isEmpty();
    }

    @Test
    void otpPlaceholderAloneCarriesNoLanguageSignal() {
        assertThat(resolver.classify("[verification code provided]")).isEmpty();
    }

    @Test
    void otpPlaceholderDoesNotResetAnEstablishedLanguage() {
        ConversationSession session = ConversationSession.newSession("s-ph", Channel.CHAT);
        session.recordUserMessage("mera order cancel karo");
        session.recordUserMessage("[verification code provided]");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.ROMAN_URDU);
    }

    @Test
    void commerceNounsAloneAreNotRomanUrduEvidence() {
        assertThat(resolver.classify("refund")).hasValue(ConversationLanguage.ENGLISH);
        assertThat(resolver.classify("cancel return order")).hasValue(ConversationLanguage.ENGLISH);
    }

    @Test
    void arabicIndicDigitsAloneAreNotUrduEvidence() {
        assertThat(resolver.classify("١٢٣٤٥٦")).isEmpty();
    }

    @Test
    void resolveUsesTheNewestUsefulSignal() {
        ConversationSession session = newSession();
        session.recordUserMessage("Where is my order?");
        session.recordUserMessage("میرا آرڈر کہاں ہے؟");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.URDU);
    }

    @Test
    void numericOtpFallsBackToThePriorUsefulUserLanguage() {
        ConversationSession session = newSession();
        session.recordUserMessage("میرا آرڈر کینسل کر دیں");
        session.recordUserMessage("123456");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.URDU);
    }

    @Test
    void orderReferenceDoesNotResetTheLanguage() {
        ConversationSession session = newSession();
        session.recordUserMessage("mera order kahan hai");
        session.recordUserMessage("ORD-10001");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.ROMAN_URDU);
    }

    @Test
    void weakEnglishConfirmationDoesNotOverrideEstablishedUrdu() {
        ConversationSession session = newSession();
        session.recordUserMessage("میرا آرڈر کینسل کر دیں");
        session.recordUserMessage("yes");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.URDU);
    }

    @Test
    void romanUrduConfirmationIsARomanUrduSignal() {
        ConversationSession session = newSession();
        session.recordUserMessage("Please cancel my order");
        session.recordUserMessage("haan");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.ROMAN_URDU);
    }

    @Test
    void assistantMessagesAreIgnoredForResolution() {
        ConversationSession session = newSession();
        session.recordUserMessage("123456");
        session.recordAssistantMessage("میرا آرڈر کہاں ہے؟");

        assertThat(resolver.resolve(session)).isEqualTo(ConversationLanguage.ENGLISH);
    }

    @Test
    void emptyHistoryFallsBackToEnglish() {
        assertThat(resolver.resolve(newSession())).isEqualTo(ConversationLanguage.ENGLISH);
    }

    private ConversationSession newSession() {
        return ConversationSession.newSession("resolver-test", Channel.CHAT);
    }
}
