package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 2: pure tier-decision logic. No I/O, no embeddings, no Spring beyond the stereotype -
 * every branch is a deterministic function of (strategy, structural features, semantic scores).
 *
 * <p>Structural priority (strongest first): multi-order reference, long compound message,
 * complex session state. In HYBRID these short-circuit before any embedding is spent; the
 * semantic path only runs when no structural signal fired.
 *
 * <p>Semantic margins are TEMPORARY PLACEHOLDERS (see {@link SemanticRoutingProperties}) -
 * not calibrated. Ambiguous margins route conservatively to TIER_2. Calibration is Phase 3.
 */
@Component
public class RoutingDecisionEngine {

    /**
     * @param strategy       the configured routing strategy.
     * @param signals        structural signals that fired (possibly empty).
     * @param semanticScores semantic class scores, or {@code null} when the semantic path did
     *                       not run (non-HYBRID strategies, or HYBRID short-circuited).
     */
    public RoutingDecision decide(
            RoutingStrategy strategy,
            Set<StructuralRoutingSignal> signals,
            SemanticScores semanticScores,
            SemanticRoutingProperties semanticProperties) {
        return switch (strategy) {
            case ALWAYS_TIER_1 -> RoutingDecision.withoutScores(ModelTier.TIER_1, RoutingReason.FORCED_TIER_1, signals);
            case ALWAYS_TIER_2 -> RoutingDecision.withoutScores(ModelTier.TIER_2, RoutingReason.FORCED_TIER_2, signals);
            case RULE_ONLY -> decideRuleOnly(signals);
            case HYBRID -> decideHybrid(signals, semanticScores, semanticProperties);
        };
    }

    private RoutingDecision decideRuleOnly(Set<StructuralRoutingSignal> signals) {
        if (signals.contains(StructuralRoutingSignal.MULTI_ORDER_REFERENCE)) {
            return RoutingDecision.withoutScores(ModelTier.TIER_2, RoutingReason.RULE_MULTI_ORDER_REFERENCE, signals);
        }
        if (signals.contains(StructuralRoutingSignal.LONG_COMPOUND_MESSAGE)) {
            return RoutingDecision.withoutScores(ModelTier.TIER_2, RoutingReason.RULE_LONG_COMPOUND_MESSAGE, signals);
        }
        if (signals.contains(StructuralRoutingSignal.COMPLEX_SESSION_STATE)) {
            return RoutingDecision.withoutScores(ModelTier.TIER_2, RoutingReason.RULE_COMPLEX_SESSION_STATE, signals);
        }
        return RoutingDecision.withoutScores(ModelTier.TIER_1, RoutingReason.RULE_DEFAULT, signals);
    }

    private RoutingDecision decideHybrid(
            Set<StructuralRoutingSignal> signals,
            SemanticScores semanticScores,
            SemanticRoutingProperties semanticProperties) {
        // Structural strong signals short-circuit: no embedding is spent.
        if (!signals.isEmpty()) {
            return decideRuleOnly(signals);
        }
        if (semanticScores == null) {
            throw new IllegalStateException("HYBRID routing reached the semantic path without semantic scores");
        }
        double margin = semanticScores.margin();
        if (margin >= semanticProperties.complexMargin()) {
            return new RoutingDecision(ModelTier.TIER_2, RoutingReason.SEMANTIC_COMPLEX,
                    semanticScores.simpleScore(), semanticScores.complexScore(), margin, Set.of());
        }
        if (margin <= -semanticProperties.simpleMargin()) {
            return new RoutingDecision(ModelTier.TIER_1, RoutingReason.SEMANTIC_SIMPLE,
                    semanticScores.simpleScore(), semanticScores.complexScore(), margin, Set.of());
        }
        return new RoutingDecision(ModelTier.TIER_2, RoutingReason.SEMANTIC_AMBIGUOUS,
                semanticScores.simpleScore(), semanticScores.complexScore(), margin, Set.of());
    }

    /** Derives the fired signal set from features; kept here so rules and tests share one definition. */
    public static Set<StructuralRoutingSignal> firedSignals(
            StructuralRoutingFeatures features, StructuralFeatureExtractor extractor, String userMessage) {
        EnumSet<StructuralRoutingSignal> signals = EnumSet.noneOf(StructuralRoutingSignal.class);
        if (extractor.isMultiOrderReference(features)) {
            signals.add(StructuralRoutingSignal.MULTI_ORDER_REFERENCE);
        }
        if (extractor.isLongCompoundMessage(features)) {
            signals.add(StructuralRoutingSignal.LONG_COMPOUND_MESSAGE);
        }
        if (extractor.isComplexSessionState(features, userMessage)) {
            signals.add(StructuralRoutingSignal.COMPLEX_SESSION_STATE);
        }
        return signals;
    }
}
