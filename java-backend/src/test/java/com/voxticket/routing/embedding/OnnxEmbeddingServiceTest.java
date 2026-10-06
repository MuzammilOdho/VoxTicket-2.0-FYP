package com.voxticket.routing.embedding;

import com.voxticket.routing.RoutingEmbeddingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: end-to-end verification of the ONNX embedding service against the real
 * {@code intfloat/multilingual-e5-small} artifacts.
 *
 * <p>Runs only when {@code -Dvoxticket.routing.model.path=/dir/with/model.onnx+tokenizer.json}
 * is given (159&nbsp;MB model, never committed). This is the integration complement to the
 * hermetic stub-based semantic tests.
 */
@EnabledIfSystemProperty(named = "voxticket.routing.model.path", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingServiceTest {

    private OnnxMultilingualE5EmbeddingService service;

    @BeforeAll
    void startService() {
        Path modelDir = Paths.get(System.getProperty("voxticket.routing.model.path"));
        service = new OnnxMultilingualE5EmbeddingService(modelDir);
    }

    @AfterAll
    void stopService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void embeddingIsNormalized384() {
        float[] vector = service.embed("Where is my order?");

        assertThat(vector).hasSize(RoutingEmbeddingService.DIMENSION);
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void sameTextEmbedsDeterministically() {
        float[] first = service.embed("Mera order kahan hai?");
        float[] second = service.embed("Mera order kahan hai?");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void urduAndEnglishParaphrasesAreCloserThanUnrelated() {
        float[] urdu = service.embed("میرا آرڈر کہاں ہے؟");
        float[] english = service.embed("Where is my order?");
        float[] unrelated = service.embed("Cancel ORD-10050 and ORD-10051, but keep ORD-10052 if it has already shipped");

        assertThat(dot(urdu, english)).isGreaterThan(dot(urdu, unrelated));
    }

    @Test
    void embeddingIsStableAcrossCalls() {
        // Guards against session-state leakage between inferences.
        float[] before = service.embed("Hello");
        service.embed("A completely different message about returns and refunds");
        float[] after = service.embed("Hello");

        assertThat(before).isEqualTo(after);
    }

    @Test
    void nullAndEmptyInputsDoNotFail() {
        assertThat(service.embed(null)).hasSize(RoutingEmbeddingService.DIMENSION);
        assertThat(service.embed("")).hasSize(RoutingEmbeddingService.DIMENSION);
        assertThat(service.embed("   ")).hasSize(RoutingEmbeddingService.DIMENSION);
    }

    @Test
    void veryLongInputTruncatesWithoutFailing() {
        String longText = "I need help with my order. ".repeat(200);

        assertThat(service.embed(longText)).hasSize(RoutingEmbeddingService.DIMENSION);
    }

    @Test
    void missingModelDirFailsWithActionableError() {
        assertThatThrownBy(() -> new OnnxMultilingualE5EmbeddingService(
                Paths.get("/definitely/not/a/real/dir")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model.onnx");
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
