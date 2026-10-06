package com.voxticket.routing;

import com.voxticket.routing.embedding.OnnxMultilingualE5EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: HYBRID initialization failures fail startup loudly - never a silent fallback to
 * RULE_ONLY, never a partially-initialized semantic stack.
 */
class RoutingStartupFailureTest {

    @TempDir
    Path tempDir;

    private static SemanticRoutingProperties semantic(String modelPath, boolean enabled) {
        return new SemanticRoutingProperties(enabled, "intfloat/multilingual-e5-small", modelPath,
                "classpath:routing/examples-v1.json", 3, 0.02, 0.02);
    }

    private static RoutingProperties routing(String modelPath, boolean enabled) {
        return new RoutingProperties(RoutingStrategy.HYBRID,
                new StructuralRoutingProperties(300, 2), semantic(modelPath, enabled));
    }

    @Test
    void missingModelDirectoryFailsFastWithActionableError() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> config.routingEmbeddingService(
                routing(tempDir.resolve("no-such-dir").toString(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model.onnx");
    }

    @Test
    void nullModelDirectoryFailsFastWithActionableError() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> config.routingEmbeddingService(routing(null, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTING_MODEL_PATH");
    }

    @Test
    void hybridWithSemanticDisabledRefusesToSilentlyDegrade() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> config.routingEmbeddingService(
                routing(tempDir.toString(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silently fall back to RULE_ONLY");
    }

    @Test
    void unsupportedRoutingModelIsRejected() {
        var config = new RoutingConfiguration();
        var props = new RoutingProperties(RoutingStrategy.HYBRID,
                new StructuralRoutingProperties(300, 2),
                new SemanticRoutingProperties(true, "some/other-model", tempDir.toString(),
                        "classpath:routing/examples-v1.json", 3, 0.02, 0.02));

        assertThatThrownBy(() -> config.routingEmbeddingService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intfloat/multilingual-e5-small");
    }

    @Test
    void embeddingServiceConstructorValidatesArtifactsDirectly() {
        Path emptyDir = tempDir.resolve("empty");

        assertThatThrownBy(() -> new OnnxMultilingualE5EmbeddingService(emptyDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model.onnx");
    }
}
