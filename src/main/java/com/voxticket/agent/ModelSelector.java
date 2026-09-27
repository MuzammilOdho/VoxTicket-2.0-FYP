package com.voxticket.agent;

import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingService;
import com.voxticket.routing.SemanticScores;
import com.voxticket.routing.StructuralFeatureExtractor;
import com.voxticket.routing.StructuralRoutingFeatures;
import com.voxticket.routing.StructuralRoutingSignal;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2: the tier router. Decides ONLY the {@link ModelTier} - never the provider or model
 * (that stays pure configuration in {@link AiTiersProperties} + {@link TierChatClientRegistry}).
 *
 * <p>Per {@link RoutingStrategy}:
 * <ul>
 *   <li>{@code ALWAYS_TIER_1} / {@code ALWAYS_TIER_2} - forced tier, no signals consulted;</li>
 *   <li>{@code RULE_ONLY} - deterministic structural signals only;</li>
 *   <li>{@code HYBRID} - structural signals short-circuit first; otherwise exactly one local
 *       e5 embedding per turn is scored against the startup-cached prototypes.</li>
 * </ul>
 *
 * <p>The router never performs authorization, ownership, OTP, confirmation, eligibility,
 * mutation-permission or provider-fallback decisions, and business risk is not a signal.
 */
@Component
public class ModelSelector {

    private static final Logger log = LoggerFactory.getLogger(ModelSelector.class);

    private final RoutingProperties routingProperties;
    private final StructuralFeatureExtractor featureExtractor;
    private final RoutingDecisionEngine decisionEngine;
    private final Optional<SemanticRoutingService> semanticService;
    private final TurnMetrics turnMetrics;

    public ModelSelector(
            RoutingProperties routingProperties,
            StructuralFeatureExtractor featureExtractor,
            RoutingDecisionEngine decisionEngine,
            Optional<SemanticRoutingService> semanticService,
            TurnMetrics turnMetrics) {
        this.routingProperties = routingProperties;
        this.featureExtractor = featureExtractor;
        this.decisionEngine = decisionEngine;
        this.semanticService = semanticService;
        this.turnMetrics = turnMetrics;
    }

    public ModelSelectionResult select(ConversationSession session, String userMessage) {
        RoutingStrategy strategy = routingProperties.strategy();

        // Forced strategies consult no signals and do no work at all.
        if (strategy == RoutingStrategy.ALWAYS_TIER_1 || strategy == RoutingStrategy.ALWAYS_TIER_2) {
            RoutingDecision forced = decisionEngine.decide(
                    strategy, Set.of(), null, routingProperties.semantic());
            record(session, strategy, forced, Set.of());
            return new ModelSelectionResult(forced.tier(), forced.reason().name());
        }

        StructuralRoutingFeatures features =
                featureExtractor.extract(session, userMessage);
        Set<StructuralRoutingSignal> signals =
                RoutingDecisionEngine.firedSignals(features, featureExtractor, userMessage);

        SemanticScores semanticScores = null;
        if (strategy == RoutingStrategy.HYBRID && signals.isEmpty()) {
            // Structural short-circuit missed: spend the single per-turn embedding here.
            semanticScores = semanticService
                    .orElseThrow(() -> new IllegalStateException(
                            "Routing strategy is HYBRID but no SemanticRoutingService is available"))
                    .score(userMessage);
        }

        RoutingDecision decision = decisionEngine.decide(
                strategy, signals, semanticScores, routingProperties.semantic());
        record(session, strategy, decision, signals);
        return new ModelSelectionResult(decision.tier(), decision.reason().name());
    }

    private void record(ConversationSession session, RoutingStrategy strategy,
                        RoutingDecision decision, Set<StructuralRoutingSignal> signals) {
        turnMetrics.recordRoutingDecision(
                strategy.name(), decision.tier().name(), decision.reason().name(), decision.margin());
        // No raw message text in logs: only the tier, the stable reason code, and the margin.
        log.info("event=routing_decision sessionId={} strategy={} tier={} reason={} margin={} signals={}",
                session.getSessionId(), strategy, decision.tier(), decision.reason(),
                formatMargin(decision.margin()), signals);
    }

    private static String formatMargin(double margin) {
        return Double.isNaN(margin) ? "n/a" : String.format("%.4f", margin);
    }
}
