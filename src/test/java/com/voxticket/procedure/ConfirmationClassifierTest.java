package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfirmationClassifierTest {

    private final ConfirmationClassifier classifier = new ConfirmationClassifier();

    @Test
    void standaloneYesVariantsAreRecognized() {
        assertThat(classifier.classify("yes")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("Yeah")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("go ahead")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("please do")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("Yes.")).isEqualTo(ConfirmationDecision.YES);
    }

    @Test
    void standaloneNoVariantsAreRecognized() {
        assertThat(classifier.classify("no")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("don't")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("never mind")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("No!")).isEqualTo(ConfirmationDecision.NO);
    }

    @Test
    void compoundConfirmationIsNoLongerAutoConsumedAndReturnsUnclear() {
        assertThat(classifier.classify("Yes, cancel it and also complain about the delay.")).isEqualTo(ConfirmationDecision.UNCLEAR);
        assertThat(classifier.classify("yes, and where is my other order?")).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @Test
    void correctiveMessagesAreNeverMisreadAsADecline() {
        assertThat(classifier.classify("no, I meant ORD-10002")).isEqualTo(ConfirmationDecision.UNCLEAR);
        assertThat(classifier.classify("no wait, the other order")).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @Test
    void sarcasticContradictionIsNeverReadAsYes() {
        assertThat(classifier.classify("yeah, right, don't cancel it")).isEqualTo(ConfirmationDecision.UNCLEAR);
    }

    @Test
    void unrelatedMessagesAreUnclear() {
        assertThat(classifier.classify("how long will the refund take?")).isEqualTo(ConfirmationDecision.UNCLEAR);
        assertThat(classifier.classify("")).isEqualTo(ConfirmationDecision.UNCLEAR);
        assertThat(classifier.classify(null)).isEqualTo(ConfirmationDecision.UNCLEAR);
    }
}