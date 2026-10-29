package com.voxticket.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Provider selection and embedding identity: the switch that decides re-ingestion. */
class EmbeddingPropertiesTest {

    @Test
    void localIdentityIsOllamaBgeM3() {
        EmbeddingProperties properties = new EmbeddingProperties(
                "local", new EmbeddingProperties.VoyageProperties("", "voyage-4-lite", "https://api.voyageai.com/v1", 5));

        assertThat(properties.isVoyage()).isFalse();
        assertThat(properties.embeddingIdentity()).isEqualTo("ollama:bge-m3:1024");
    }

    @Test
    void voyageIdentityIncludesModel() {
        EmbeddingProperties properties = new EmbeddingProperties(
                "voyage", new EmbeddingProperties.VoyageProperties("key", "voyage-4", "https://api.voyageai.com/v1", 5));

        assertThat(properties.isVoyage()).isTrue();
        assertThat(properties.embeddingIdentity()).isEqualTo("voyage:voyage-4:1024");
    }

    @Test
    void voyageDefaultsToVoyage4LiteWhenSubtreeAbsent() {
        EmbeddingProperties properties = new EmbeddingProperties("local", null);

        assertThat(properties.voyage().model()).isEqualTo("voyage-4-lite");
        assertThat(properties.voyage().baseUrl()).isEqualTo("https://api.voyageai.com/v1");
        assertThat(properties.voyage().timeoutSeconds()).isEqualTo(5);
    }

    @Test
    void unknownProviderFailsFast() {
        assertThatThrownBy(() -> new EmbeddingProperties(
                "cohere", new EmbeddingProperties.VoyageProperties("", "voyage-4-lite", "https://api.voyageai.com/v1", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local");
    }
}
