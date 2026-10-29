package com.voxticket.routing;

import com.voxticket.routing.embedding.OnnxMultilingualE5EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2: HYBRID initialization failures fail startup loudly - never a silent fallback to
 * RULE_ONLY, never a partially-initialized semantic stack.
 *
 * <p>RoutingConfiguration.routingEmbeddingService now also takes the routing
 * provider (from voxticket.routing.provider) and the Voyage settings; these
 * tests exercise the local path, so they pass "local" and null Voyage
 * settings, which the local path never touches.
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

    private static RoutingEmbeddingService localService(RoutingConfiguration config, RoutingProperties props) {
        // Null Voyage settings are safe here: the local path never touches
        // them (they are only used on the voyage branch, which constructs
        // its own client and fails fast on a blank key).
        return config.routingEmbeddingService(props, "local", "voyage-4-lite", null, null, 5);
    }

    @Test
    void missingModelDirectoryFailsFastWithActionableError() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> localService(config,
                routing(tempDir.resolve("no-such-dir").toString(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model.onnx");
    }

    @Test
    void nullModelDirectoryFailsFastWithActionableError() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> localService(config, routing(null, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTING_MODEL_PATH");
    }

    @Test
    void hybridWithSemanticDisabledRefusesToSilentlyDegrade() {
        var config = new RoutingConfiguration();

        assertThatThrownBy(() -> localService(config, routing(tempDir.toString(), false)))
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

        assertThatThrownBy(() -> localService(config, props))
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
