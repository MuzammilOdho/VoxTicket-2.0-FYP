package com.voxticket.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG embedding provider selection (pgvector). Routing has its own,
 * independent provider and model under {@code voxticket.routing} (see
 * RoutingConfiguration): setting this to voyage moves RAG only, and
 * setting routing to voyage moves routing only.
 *
 * <p>Default is {@code local}: RAG via Ollama bge-m3. Set
 * {@code VOXTICKET_EMBEDDING_PROVIDER=voyage} and {@code VOYAGE_API_KEY}
 * to move RAG to Voyage voyage-4-lite (1024 dims, no pgvector
 * migration; $0.02/1M tokens, 200M free per Voyage/MongoDB billing
 * docs). Upgrade: change {@code VOYAGE_MODEL} to voyage-4 ($0.06) or
 * voyage-4-large ($0.12), same 1024 dims; ingestion re-embeds
 * automatically because the embedding identity includes the model
 * name. When provider is voyage and the key is missing, startup
 * fails fast (see {@link EmbeddingConfiguration}) - it never silently
 * falls back to local, because that would mix vector spaces.
 */
@ConfigurationProperties(prefix = "voxticket.embedding")
public record EmbeddingProperties(
        @DefaultValue("local") String provider,
        @NestedConfigurationProperty VoyageProperties voyage) {

    public EmbeddingProperties {
        if (voyage == null) {
            voyage = new VoyageProperties("", "voyage-4-lite", "https://api.voyageai.com/v1", 5);
        }
        if (provider == null || (!provider.equalsIgnoreCase("local") && !provider.equalsIgnoreCase("voyage"))) {
            throw new IllegalArgumentException(
                    "voxticket.embedding.provider must be 'local' or 'voyage', got '" + provider + "'");
        }
    }

    public boolean isVoyage() {
        return "voyage".equalsIgnoreCase(provider);
    }

    /**
     * Identity of the vectors the current configuration produces, stored
     * on every ingestion-managed RAG row. A row is reused only when both
     * its content hash AND this identity match; otherwise it is
     * re-embedded. That is what makes a provider/model switch safe: old
     * bge-m3 rows are never queried with Voyage vectors, and vice versa.
     */
    public String embeddingIdentity() {
        return isVoyage() ? "voyage:" + voyage.model() + ":1024" : "ollama:bge-m3:1024";
    }

    public record VoyageProperties(
            @DefaultValue("") String apiKey,
            @DefaultValue("voyage-4-lite") String model,
            @DefaultValue("https://api.voyageai.com/v1") String baseUrl,
            @DefaultValue("5") int timeoutSeconds) {
    }
}
