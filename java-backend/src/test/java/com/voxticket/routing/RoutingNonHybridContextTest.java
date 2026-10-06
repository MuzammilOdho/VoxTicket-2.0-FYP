package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.agent.ModelSelectionResult;
import com.voxticket.agent.ModelSelector;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.observability.TurnMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 2 regression test: the non-HYBRID routing strategies ({@code RULE_ONLY},
 * {@code ALWAYS_TIER_1}, {@code ALWAYS_TIER_2}) must boot a Spring context WITHOUT any
 * semantic routing resources - no JSON example-dataset loading, no {@code ROUTING_MODEL_PATH},
 * no tokenizer, no ONNX model, and no semantic beans at all.
 *
 * <p>This is the regression net for the Boot 4 startup failure where
 * {@code RoutingExampleLoader} was an unconditional component depending on the Jackson 2
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean, which Spring Boot 4 does not
 * provide (Boot 4 auto-configures Jackson 3, {@code tools.jackson}). Every full-context test
 * failed with {@code Failed to load ApplicationContext} because of it. The loader is now
 * Jackson 3 based AND conditional on {@code HYBRID}, so these strategies boot with neither
 * a Jackson bean nor any model resource present - the runner below deliberately provides
 * neither.
 */
class RoutingNonHybridContextTest {

    @ParameterizedTest
    @ValueSource(strings = {"RULE_ONLY", "ALWAYS_TIER_1", "ALWAYS_TIER_2"})
    void nonHybridStrategyStartsWithoutSemanticResources(String strategy) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RoutingConfiguration.class,
                        RoutingExampleLoader.class,
                        ModelSelector.class,
                        StructuralFeatureExtractor.class,
                        RoutingDecisionEngine.class,
                        TurnMetrics.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(RoutingPropertiesConfiguration.class)
                // Deliberately NO tools.jackson ObjectMapper bean and NO model-path property:
                // proving these strategies need neither.
                .withPropertyValues("voxticket.ai.selector.strategy=" + strategy)
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelSelector.class);
                    assertThat(context).hasSingleBean(RoutingProperties.class);
                    assertThat(context.getBean(RoutingProperties.class).strategy().name())
                            .isEqualTo(strategy);

                    // No semantic beans may exist in these strategies.
                    assertThat(context).doesNotHaveBean(RoutingExampleLoader.class);
                    assertThat(context).doesNotHaveBean(RoutingEmbeddingService.class);
                    assertThat(context).doesNotHaveBean(RoutingExampleIndex.class);
                    assertThat(context).doesNotHaveBean(SemanticRoutingService.class);

                    // And routing still works end to end.
                    ModelSelector selector = context.getBean(ModelSelector.class);
                    ConversationSession session = ConversationSession.newSession("s1", Channel.CHAT);
                    ModelSelectionResult result = selector.select(session, "Where is my order?");
                    assertThat(result.tier()).isNotNull();
                    assertThat(result.reason()).isNotBlank();
                });
    }

    @Configuration
    @EnableConfigurationProperties(RoutingProperties.class)
    static class RoutingPropertiesConfiguration {
    }
}
