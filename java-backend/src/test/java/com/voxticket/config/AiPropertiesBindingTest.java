package com.voxticket.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.rag.RagProperties;
import com.voxticket.safety.PromptGuardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves model configuration is genuinely externalized: overriding these properties changes what the app uses without touching any Java code. */
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "voxticket.ai.tier1-model=test/tier1-override",
        "voxticket.ai.tier2-model=test/tier2-override",
        "voxticket.ai.temperature=0.9",
        "voxticket.ai.suppress-reasoning=false",
        "voxticket.ai.selector.strategy=RULE_ONLY",
        "voxticket.ai.selector.structural.long-message-threshold=50",
        "voxticket.ai.selector.structural.min-order-references-for-tier2=3",
        "voxticket.ai.selector.semantic.top-k-per-class=5",
        "voxticket.ai.selector.semantic.complex-margin=0.05",
        "voxticket.rag.top-k=7",
        "voxticket.rag.similarity-threshold=0.8",
        "voxticket.safety.prompt-guard.ml-threshold=0.75",
        "voxticket.safety.prompt-guard.ml-max-tokens=5"
})
@SpringBootTest
class AiPropertiesBindingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private RoutingProperties routingProperties;
    @Autowired
    private RagProperties ragProperties;
    @Autowired
    private PromptGuardProperties promptGuardProperties;


    @Test
    void routingConfigurationIsOverriddenPurelyThroughConfiguration() {
        assertThat(routingProperties.strategy()).isEqualTo(RoutingStrategy.RULE_ONLY);
        assertThat(routingProperties.structural().longMessageThreshold()).isEqualTo(50);
        assertThat(routingProperties.structural().minOrderReferencesForTier2()).isEqualTo(3);
        assertThat(routingProperties.semantic().topKPerClass()).isEqualTo(5);
        assertThat(routingProperties.semantic().complexMargin()).isEqualTo(0.05);
    }

    @Test
    void ragRetrievalParametersAreOverriddenPurelyThroughConfiguration() {
        assertThat(ragProperties.topK()).isEqualTo(7);
        assertThat(ragProperties.similarityThreshold()).isEqualTo(0.8);
    }

    @Test
    void promptGuardParametersAreOverriddenPurelyThroughConfiguration() {
        assertThat(promptGuardProperties.mlThreshold()).isEqualTo(0.75);
        assertThat(promptGuardProperties.mlMaxTokens()).isEqualTo(5);
    }
}