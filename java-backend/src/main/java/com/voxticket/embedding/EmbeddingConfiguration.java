package com.voxticket.embedding;

import java.time.Duration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Voyage beans, active ONLY when {@code voxticket.embedding.provider=voyage}.
 * With the default {@code local} provider this class creates nothing:
 * RAG keeps the Ollama bge-m3 EmbeddingModel from Spring AI auto-configuration
 * (and the {@code spring.ai.model.embedding=ollama} selector in
 * application.yaml stays untouched, so SpringAiModelAutoConfigDisabledTest
 * keeps passing), and routing keeps the ONNX service.
 *
 * <p>When voyage is active, the Voyage model is {@link Primary} so
 * PgVectorStore auto-configuration picks it over the Ollama model instead
 * of failing on two EmbeddingModel beans. The client bean fails fast on
 * a missing key, at startup, not on the first turn.
 */
@Configuration
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "voxticket.embedding.provider", havingValue = "voyage")
    public VoyageEmbeddingClient voyageEmbeddingClient(EmbeddingProperties properties) {
        EmbeddingProperties.VoyageProperties voyage = properties.voyage();
        if (voyage.apiKey() == null || voyage.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "voxticket.embedding.provider=voyage but VOYAGE_API_KEY is not set - "
                            + "set it in the environment, or set VOXTICKET_EMBEDDING_PROVIDER=local");
        }
        return new VoyageEmbeddingClient(
                voyage.baseUrl(), voyage.apiKey(), voyage.model(),
                Duration.ofSeconds(voyage.timeoutSeconds()));
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "voxticket.embedding.provider", havingValue = "voyage")
    public EmbeddingModel voyageEmbeddingModel(VoyageEmbeddingClient client) {
        return new VoyageEmbeddingModel(client);
    }
}
