package com.voxticket.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfirmationClassifierTest {

    private final ConfirmationClassifier classifier = new ConfirmationClassifier();

    @Test
    void plainYesVariantsAreRecognized() {
        assertThat(classifier.classify("yes")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("Yeah")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("go ahead")).isEqualTo(ConfirmationDecision.YES);
        assertThat(classifier.classify("please do")).isEqualTo(ConfirmationDecision.YES);
    }

    @Test
    void plainNoVariantsAreRecognized() {
        assertThat(classifier.classify("no")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("don't")).isEqualTo(ConfirmationDecision.NO);
        assertThat(classifier.classify("never mind")).isEqualTo(ConfirmationDecision.NO);
    }

    @Test
    void compoundConfirmationIsRecognizedAsYes() {
        assertThat(classifier.classify("Yes, cancel it and also complain about the delay.")).isEqualTo(ConfirmationDecision.YES);
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