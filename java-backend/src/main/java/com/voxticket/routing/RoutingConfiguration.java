package com.voxticket.routing;

import com.voxticket.routing.embedding.OnnxMultilingualE5EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 2: wires the semantic routing stack. All beans here exist ONLY when
 * {@code voxticket.ai.selector.strategy=HYBRID} (the default), so RULE_ONLY/forced-strategy
 * deployments - including every test context - never touch the model, the tokenizer, or the
 * example dataset.
 *
 * <p>Startup order inside HYBRID: embedding service (fails fast on missing model/tokenizer)
 * &rarr; example loader &rarr; prototype index (embeds every example exactly once) &rarr;
 * semantic service. A HYBRID boot with an unusable semantic stack fails startup loudly;
 * it NEVER silently degrades to RULE_ONLY.
 */
@Configuration
public class RoutingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RoutingConfiguration.class);
    private static final String STRATEGY_PROPERTY = "voxticket.ai.selector.strategy";

    @Bean
    @ConditionalOnProperty(name = STRATEGY_PROPERTY, havingValue = "HYBRID", matchIfMissing = true)
    public RoutingEmbeddingService routingEmbeddingService(RoutingProperties routingProperties) {
        SemanticRoutingProperties semantic = routingProperties.semantic();
        if (!semantic.enabled()) {
            throw new IllegalStateException("Routing strategy is HYBRID but "
                    + "voxticket.ai.selector.semantic.enabled=false - HYBRID requires the semantic "
                    + "stack; refusing to silently fall back to RULE_ONLY");
        }
        String modelPath = semantic.modelPath();
        if (!semantic.model().equals("intfloat/multilingual-e5-small")) {
            throw new IllegalStateException("Unsupported routing model '" + semantic.model()
                    + "' - Phase 2 supports only intfloat/multilingual-e5-small");
        }
        Path modelDir = modelPath == null || modelPath.isBlank() ? null : Path.of(modelPath);
        return new OnnxMultilingualE5EmbeddingService(modelDir);
    }

    @Bean
    @ConditionalOnProperty(name = STRATEGY_PROPERTY, havingValue = "HYBRID", matchIfMissing = true)
    public RoutingExampleIndex routingExampleIndex(
            RoutingExampleLoader loader,
            RoutingProperties routingProperties,
            RoutingEmbeddingService routingEmbeddingService) {
        SemanticRoutingProperties semantic = routingProperties.semantic();
        Instant start = Instant.now();
        List<RoutingExample> examples = loader.load(semantic.examples());
        RoutingExampleIndex index = RoutingExampleIndex.build(examples, routingEmbeddingService);
        log.info("event=routing_prototypes_ready count={} tier1={} tier2={} tookMs={}",
                examples.size(),
                index.prototypeCount(com.voxticket.agent.ModelTier.TIER_1),
                index.prototypeCount(com.voxticket.agent.ModelTier.TIER_2),
                Duration.between(start, Instant.now()).toMillis());
        return index;
    }

    @Bean
    @ConditionalOnProperty(name = STRATEGY_PROPERTY, havingValue = "HYBRID", matchIfMissing = true)
    public SemanticRoutingService semanticRoutingService(
            RoutingEmbeddingService routingEmbeddingService,
            RoutingExampleIndex routingExampleIndex,
            RoutingProperties routingProperties) {
        return new SemanticRoutingService(routingEmbeddingService, routingExampleIndex,
                routingProperties.semantic());
    }
}
