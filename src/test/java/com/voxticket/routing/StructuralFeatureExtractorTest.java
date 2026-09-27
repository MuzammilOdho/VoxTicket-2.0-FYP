package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.procedure.ProcedureState;
import com.voxticket.procedure.ProcedureType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 2: structural feature extraction is deterministic, language-independent, and
 * deliberately blind to business risk, authorization, and OTP state.
 */
class StructuralFeatureExtractorTest {

    private final StructuralFeatureExtractor extractor = new StructuralFeatureExtractor(
            new RoutingProperties(RoutingStrategy.RULE_ONLY, new StructuralRoutingProperties(300, 2),
                    new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", "",
                            "classpath:routing/examples-v1.json", 3, 0.02, 0.02)));

    private static ConversationSession session() {
        return ConversationSession.newSession("s1", Channel.CHAT);
    }

    private static ProcedureState procedure() {
        return new ProcedureState(ProcedureType.CLAIM, null, Map.of(), IdentityAssurance.PHONE_MATCHED);
    }

    @Test
    void countsDistinctOrderReferencesCaseInsensitively() {
        var features = extractor.extract(session(), "ORD-10001 and ord-10002, also ORD-10001 again");

        assertThat(features.distinctOrderReferences()).isEqualTo(2);
        assertThat(extractor.isMultiOrderReference(features)).isTrue();
    }

    @Test
    void singleOrderReferenceIsNotMultiOrder() {
        var features = extractor.extract(session(), "Cancel ORD-10001 please");

        assertThat(features.distinctOrderReferences()).isEqualTo(1);
        assertThat(extractor.isMultiOrderReference(features)).isFalse();
    }

    @Test
    void noOrderReferenceCountsZero() {
        var features = extractor.extract(session(), "Where is my order?");

        assertThat(features.distinctOrderReferences()).isZero();
        assertThat(extractor.isMultiOrderReference(features)).isFalse();
    }

    @Test
    void orderReferencesWorkInUrduAndRomanUrdu() {
        var urdu = extractor.extract(session(), "ORD-10001 اور ORD-10002 منسوخ کر دیں");
        var roman = extractor.extract(session(), "ORD-10001 aur ORD-10002 cancel kar dein");

        assertThat(extractor.isMultiOrderReference(urdu)).isTrue();
        assertThat(extractor.isMultiOrderReference(roman)).isTrue();
    }

    @Test
    void longMessageSignalHonorsConfiguredThreshold() {
        var features = extractor.extract(session(), "x".repeat(300));

        assertThat(features.messageLength()).isEqualTo(300);
        assertThat(extractor.isLongCompoundMessage(features)).isTrue();
        assertThat(extractor.isLongCompoundMessage(extractor.extract(session(), "x".repeat(299)))).isFalse();
    }

    @Test
    void complexSessionStateNeedsBothSlotsAndSubstantiveMessage() {
        ConversationSession both = session();
        both.beginProcedure(procedure());
        both.beginProcedure(procedure());
        String substantive = "Actually also check the second item in that same order please";

        var features = extractor.extract(both, substantive);

        assertThat(features.hasActiveProcedure()).isTrue();
        assertThat(features.hasPausedProcedure()).isTrue();
        assertThat(extractor.isComplexSessionState(features, substantive)).isTrue();
        // A bare ack is not a substantive new request.
        assertThat(extractor.isComplexSessionState(features, "yes")).isFalse();
    }

    @Test
    void complexSessionStateNeedsBothProcedureSlots() {
        ConversationSession onlyActive = session();
        onlyActive.beginProcedure(procedure());
        var features = extractor.extract(onlyActive, "This is a long substantive message about my claim");

        assertThat(extractor.isComplexSessionState(features, "This is a long substantive message about my claim"))
                .isFalse();
        assertThat(extractor.isComplexSessionState(
                extractor.extract(session(), "This is a long substantive message about my claim"),
                "This is a long substantive message about my claim")).isFalse();
    }

    @Test
    void featuresExposeProcedureSlotPresence() {
        ConversationSession both = session();
        both.beginProcedure(procedure());
        both.beginProcedure(procedure());

        var features = extractor.extract(both, "hi");

        assertThat(features.hasActiveProcedure()).isTrue();
        assertThat(features.hasPausedProcedure()).isTrue();
    }

    @Test
    void nullMessageIsHandledAsEmpty() {
        var features = extractor.extract(session(), null);

        assertThat(features.messageLength()).isZero();
        assertThat(features.distinctOrderReferences()).isZero();
        assertThat(extractor.isMultiOrderReference(features)).isFalse();
        assertThat(extractor.isLongCompoundMessage(features)).isFalse();
    }

    @Test
    void businessRiskIsNotASignal() {
        // "Cancel ORD-10001" involves a mutation + OTP downstream, yet structurally it is simple.
        var features = extractor.extract(session(), "Cancel ORD-10001");

        assertThat(extractor.isMultiOrderReference(features)).isFalse();
        assertThat(extractor.isLongCompoundMessage(features)).isFalse();
        assertThat(extractor.isComplexSessionState(features, "Cancel ORD-10001")).isFalse();
    }
}
