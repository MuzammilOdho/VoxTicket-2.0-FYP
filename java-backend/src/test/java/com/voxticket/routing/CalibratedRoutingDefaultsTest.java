package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.voxticket.agent.ModelTier;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: pins the frozen calibration outcome in the production defaults.
 *
 * <p>{@code simpleMargin} was calibrated on the 120-example held-out validation
 * set (maximize balanced accuracy; tie-breaks: lower under-routing, higher
 * macro-F1, lower TIER_2 selection rate). {@code complexMargin} stays 0.02: it
 * only renames the TIER_2 reason and is not identifiable from binary tier labels.
 */
class CalibratedRoutingDefaultsTest {

    /** The exact validation-selected threshold (a margin-derived midpoint). */
    static final double CALIBRATED_SIMPLE_MARGIN = 0.01135858377758675;

    @Test
    void compactConstructorDefaultsCarryCalibratedMargin() {
        RoutingProperties props = new RoutingProperties(RoutingStrategy.HYBRID, null, null);
        assertThat(props.semantic().simpleMargin())
                .isCloseTo(CALIBRATED_SIMPLE_MARGIN, within(0.0));
        assertThat(props.semantic().complexMargin()).isEqualTo(0.02);
        assertThat(props.semantic().topKPerClass()).isEqualTo(3);
        assertThat(props.structural().longMessageThreshold()).isEqualTo(300);
        assertThat(props.structural().minOrderReferencesForTier2()).isEqualTo(2);
    }

    @Test
    void calibratedMarginWidensTier1BandRelativeToPlaceholder() {
        // margin -0.015: TIER_1 under the calibrated margin, TIER_2 under the old placeholder.
        RoutingDecisionEngine engine = new RoutingDecisionEngine();
        SemanticRoutingProperties calibrated = new SemanticRoutingProperties(true,
                "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json",
                3, 0.02, CALIBRATED_SIMPLE_MARGIN);
        SemanticRoutingProperties placeholder = new SemanticRoutingProperties(true,
                "intfloat/multilingual-e5-small", "", "classpath:routing/examples-v1.json",
                3, 0.02, 0.02);
        SemanticScores scores = new SemanticScores(0.515, 0.500, -0.015);
        assertThat(engine.decide(RoutingStrategy.HYBRID, Set.of(), scores, calibrated).tier())
                .isEqualTo(ModelTier.TIER_1);
        assertThat(engine.decide(RoutingStrategy.HYBRID, Set.of(), scores, placeholder).tier())
                .isEqualTo(ModelTier.TIER_2);
    }
}
