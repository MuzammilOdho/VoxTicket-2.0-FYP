package com.voxticket.routing.eval;

import com.voxticket.agent.ModelSelectionResult;
import com.voxticket.agent.ModelTier;
import com.voxticket.agent.ModelSelector;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingExample;
import com.voxticket.routing.RoutingExampleIndex;
import com.voxticket.routing.RoutingExampleLoader;
import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import com.voxticket.routing.SemanticRoutingService;
import com.voxticket.routing.SemanticScores;
import com.voxticket.routing.StructuralFeatureExtractor;
import com.voxticket.routing.StructuralRoutingFeatures;
import com.voxticket.routing.StructuralRoutingSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3: evaluation composition over the REAL production routing components.
 *
 * <p>This class does not reimplement routing. Scoring uses
 * {@link StructuralFeatureExtractor}, {@link RoutingExampleIndex} and
 * {@link SemanticRoutingService}; every tier verdict comes from
 * {@link RoutingDecisionEngine#decide}, the same method the production
 * {@link ModelSelector} calls. {@link #parityCheck} machine-verifies that the
 * harness verdicts equal {@code ModelSelector.select} verdicts on the same inputs.
 *
 * <p>Constructed directly (no Spring context): router-only evaluation needs no DB,
 * no network and no LLM provider.
 */
public final class Phase3Router implements AutoCloseable {

    private final RoutingProperties properties;
    private final StructuralFeatureExtractor extractor;
    private final RoutingDecisionEngine engine;
    private final SemanticRoutingService semanticService;
    private final CachingEmbeddingService embeddingService;
    private final ModelSelector modelSelector;

    private Phase3Router(RoutingProperties properties,
                         CachingEmbeddingService embeddingService,
                         List<RoutingExample> prototypes) {
        this.properties = properties;
        this.extractor = new StructuralFeatureExtractor(properties);
        this.engine = new RoutingDecisionEngine();
        this.embeddingService = embeddingService;
        RoutingExampleIndex index = RoutingExampleIndex.build(prototypes, embeddingService);
        this.semanticService = new SemanticRoutingService(
                embeddingService, index, properties.semantic());
        this.modelSelector = new ModelSelector(properties, extractor, engine,
                Optional.of(semanticService), new TurnMetrics(new SimpleMeterRegistry()));
    }

    /**
     * Builds the router over the frozen 96 Phase 2 prototypes.
     *
     * @param properties       routing properties (strategy + structural + semantic knobs)
     * @param embeddingService the embedding backend (real ONNX, or a stub in hermetic tests);
     *                         wrapped in a call-counting cache
     */
    public static Phase3Router create(RoutingProperties properties,
                                      com.voxticket.routing.RoutingEmbeddingService embeddingService) {
        RoutingExampleLoader loader =
                new RoutingExampleLoader(new DefaultResourceLoader(), new JsonMapper());
        List<RoutingExample> prototypes = loader.load(properties.semantic().examples());
        return create(properties, embeddingService, prototypes);
    }

    /**
     * Builds the router over an explicit prototype list (lets callers time prototype
     * embedding separately from model initialization).
     */
    public static Phase3Router create(RoutingProperties properties,
                                      com.voxticket.routing.RoutingEmbeddingService embeddingService,
                                      List<RoutingExample> prototypes) {
        return new Phase3Router(properties, new CachingEmbeddingService(embeddingService), prototypes);
    }

    /** Number of real (uncached) embedding calls made so far - the "one embedding per turn" budget. */
    public long embeddingCalls() {
        return embeddingService.realCalls();
    }

    public RoutingDecisionEngine decisionEngine() {
        return engine;
    }

    public StructuralFeatureExtractor featureExtractor() {
        return extractor;
    }

    public SemanticRoutingService semanticRoutingService() {
        return semanticService;
    }

    /** The call-counting cache around the real embedding backend (test-only). */
    public CachingEmbeddingService cachingEmbeddingService() {
        return embeddingService;
    }

    public RoutingProperties routingProperties() {
        return properties;
    }

    /**
     * Scores every example through the production HYBRID path: structural features first,
     * then exactly one {@code SemanticRoutingService.score} call per example whose turn was
     * not structurally short-circuited.
     */
    public List<ScoredExample> scoreForHybrid(List<EvalExample> examples) {
        List<ScoredExample> scored = new ArrayList<>(examples.size());
        for (EvalExample example : examples) {
            ConversationSession session = ConversationSession.newSession(
                    "phase3-" + example.id(), Channel.CHAT);
            StructuralRoutingFeatures features = extractor.extract(session, example.text());
            Set<StructuralRoutingSignal> signals =
                    RoutingDecisionEngine.firedSignals(features, extractor, example.text());
            SemanticScores scores = null;
            if (signals.isEmpty()) {
                scores = semanticService.score(example.text());
            }
            scored.add(new ScoredExample(example, signals, scores));
        }
        return scored;
    }

    /**
     * The production tier verdict for one scored example under the given strategy and
     * semantic knobs. Delegates to {@link RoutingDecisionEngine#decide} - the exact
     * production decision function, parameterized for calibration sweeps.
     */
    public RoutingDecision decide(ScoredExample scored,
                                  RoutingStrategy strategy,
                                  SemanticRoutingProperties semanticProps) {
        return engine.decide(strategy, scored.signals(), scored.semanticScores(), semanticProps);
    }

    /**
     * Machine-checks that the harness composition agrees with the production
     * {@link ModelSelector}: same tier and reason for every example/strategy pair.
     * Returns human-readable mismatches (empty = full parity).
     */
    public List<String> parityCheck(List<EvalExample> examples) {
        List<String> mismatches = new ArrayList<>();
        for (EvalExample example : examples) {
            for (RoutingStrategy strategy : RoutingStrategy.values()) {
                RoutingProperties strategyProps = new RoutingProperties(
                        strategy, properties.structural(), properties.semantic());
                ModelSelector selector = new ModelSelector(strategyProps, extractor, engine,
                        Optional.of(semanticService), new TurnMetrics(new SimpleMeterRegistry()));
                ConversationSession session = ConversationSession.newSession(
                        "parity-" + example.id(), Channel.CHAT);
                ModelSelectionResult actual = selector.select(session, example.text());

                ConversationSession session2 = ConversationSession.newSession(
                        "phase3-" + example.id(), Channel.CHAT);
                StructuralRoutingFeatures features = extractor.extract(session2, example.text());
                Set<StructuralRoutingSignal> signals =
                        RoutingDecisionEngine.firedSignals(features, extractor, example.text());
                SemanticScores scores = null;
                if (strategy == RoutingStrategy.HYBRID && signals.isEmpty()) {
                    scores = semanticService.score(example.text());
                }
                RoutingDecision expected =
                        engine.decide(strategy, signals, scores, strategyProps.semantic());
                if (actual.tier() != expected.tier()
                        || !actual.reason().equals(expected.reason().name())) {
                    mismatches.add(example.id() + "/" + strategy + ": selector=" + actual
                            + " harness=" + expected.tier() + "/" + expected.reason());
                }
            }
        }
        return mismatches;
    }

    @Override
    public void close() {
        embeddingService.closeDelegate();
    }
}
