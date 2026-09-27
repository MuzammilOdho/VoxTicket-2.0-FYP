package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.agent.ModelSelectionResult;
import com.voxticket.agent.ModelSelector;
import com.voxticket.agent.ModelTier;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 2: HYBRID boots the FULL semantic stack against the real ONNX model and proves the
 * Jackson 3 example loader works end to end.
 *
 * <p>Opt-in like the other model-backed tests: it only runs when
 * {@code -Dvoxticket.routing.model.path=/abs/path/to/model-dir} points at a directory
 * containing {@code model.onnx} and {@code tokenizer.json} (intfloat/multilingual-e5-small).
 * HYBRID fail-fast is preserved - a bad path fails context startup instead of silently
 * degrading.
 */
@EnabledIfSystemProperty(named = "voxticket.routing.model.path", matches = ".+")
class RoutingHybridContextTest {

    @Test
    void hybridBootsFullSemanticStackWithJackson3ExampleLoader() {
        String modelPath = System.getProperty("voxticket.routing.model.path");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RoutingConfiguration.class,
                        RoutingExampleLoader.class,
                        ModelSelector.class,
                        StructuralFeatureExtractor.class,
                        RoutingDecisionEngine.class,
                        TurnMetrics.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                // Mirrors Boot 4's JacksonAutoConfiguration: a Jackson 3 ObjectMapper bean.
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(RoutingPropertiesConfiguration.class)
                // Default strategy is HYBRID; only the model location is supplied.
                .withPropertyValues("voxticket.ai.selector.semantic.model-path=" + modelPath)
                .run(context -> {
                    // The whole semantic stack is present...
                    assertThat(context).hasSingleBean(RoutingExampleLoader.class);
                    assertThat(context).hasSingleBean(RoutingEmbeddingService.class);
                    assertThat(context).hasSingleBean(RoutingExampleIndex.class);
                    assertThat(context).hasSingleBean(SemanticRoutingService.class);

                    // ...the Jackson 3 loader parsed the versioned dataset: 48 + 48 prototypes.
                    RoutingExampleIndex index = context.getBean(RoutingExampleIndex.class);
                    assertThat(index.prototypeCount(ModelTier.TIER_1)).isEqualTo(48);
                    assertThat(index.prototypeCount(ModelTier.TIER_2)).isEqualTo(48);

                    // ...and a real turn flows through the semantic stack: one message,
                    // one embedding, a SEMANTIC_* decision.
                    ModelSelector selector = context.getBean(ModelSelector.class);
                    ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
                    ModelSelectionResult result = selector.select(session, "Where is my order?");
                    assertThat(result.tier()).isNotNull();
                    assertThat(result.reason()).startsWith("SEMANTIC_");
                });
    }

    @Configuration
    @EnableConfigurationProperties(RoutingProperties.class)
    static class RoutingPropertiesConfiguration {
    }
}
