package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import java.util.List;

/**
 * Phase 2: semantic tier scoring - the most expensive step in routing, so it runs at most once
 * per turn and only when the cheap structural signals did not already decide.
 *
 * <p>Embeds ONLY the current user message (never conversation history) via
 * {@link RoutingEmbeddingService} and scores it against the startup-cached
 * {@link RoutingExampleIndex} prototypes. No routing DB, no pgvector, no network, no ANN.
 *
 * <p>Not a component: the single instance is the conditional {@code semanticRoutingService}
 * bean in {@link RoutingConfiguration}, which exists only for HYBRID.
 */
public class SemanticRoutingService {

    private final RoutingEmbeddingService embeddingService;
    private final RoutingExampleIndex exampleIndex;
    private final SemanticRoutingProperties properties;

    public SemanticRoutingService(
            RoutingEmbeddingService embeddingService,
            RoutingExampleIndex exampleIndex,
            SemanticRoutingProperties properties) {
        this.embeddingService = embeddingService;
        this.exampleIndex = exampleIndex;
        this.properties = properties;
    }

    /**
     * Scores one user message. Exactly one {@link RoutingEmbeddingService#embed} call.
     *
     * @throws RoutingInferenceException if embedding fails; callers must NOT catch this and
     *         silently fall back to another strategy - the failure must stay observable.
     */
    public SemanticScores score(String userMessage) {
        float[] query = embeddingService.embed(userMessage == null ? "" : userMessage);
        int topK = properties.topKPerClass();
        double simple = exampleIndex.topKMeanSimilarity(query, ModelTier.TIER_1, topK);
        double complex = exampleIndex.topKMeanSimilarity(query, ModelTier.TIER_2, topK);
        return new SemanticScores(simple, complex, complex - simple);
    }

    /** Prototype counts per tier, for startup logging and tests. */
    public List<String> describeIndex() {
        return List.of(
                ModelTier.TIER_1 + "=" + exampleIndex.prototypeCount(ModelTier.TIER_1),
                ModelTier.TIER_2 + "=" + exampleIndex.prototypeCount(ModelTier.TIER_2));
    }
}
