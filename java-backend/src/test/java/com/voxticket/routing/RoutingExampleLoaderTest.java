package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.agent.ModelTier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 2: the example dataset loads strictly - duplicates, unknown tiers/languages, blank
 * texts and missing classes all fail fast instead of silently degrading routing.
 */
class RoutingExampleLoaderTest {

    @TempDir
    Path tempDir;

    private final RoutingExampleLoader loader =
            new RoutingExampleLoader(new DefaultResourceLoader(), JsonMapper.builder().build());

    @Test
    void loadsVersionedProductionDataset() {
        List<RoutingExample> examples = loader.load("classpath:routing/examples-v1.json");

        assertThat(examples).hasSize(96);
        Map<ModelTier, Long> byTier = examples.stream()
                .collect(Collectors.groupingBy(RoutingExample::tier, Collectors.counting()));
        assertThat(byTier.get(ModelTier.TIER_1)).isEqualTo(48);
        assertThat(byTier.get(ModelTier.TIER_2)).isEqualTo(48);
        Map<RoutingExampleLanguage, Long> byLanguage = examples.stream()
                .collect(Collectors.groupingBy(RoutingExample::language, Collectors.counting()));
        assertThat(byLanguage.values()).allSatisfy(count -> assertThat(count).isEqualTo(24));
        assertThat(examples).allSatisfy(e -> {
            assertThat(e.id()).isNotBlank();
            assertThat(e.text()).isNotBlank();
        });
        assertThat(examples.stream().map(RoutingExample::id).distinct()).hasSize(96);
    }

    @Test
    void rejectsDuplicateIds() throws Exception {
        Path file = writeJson("""
                {"examples": [
                  {"id": "dup", "tier": "TIER_1", "language": "ENGLISH", "tags": [], "text": "hi"},
                  {"id": "dup", "tier": "TIER_2", "language": "ENGLISH", "tags": [], "text": "hello"}
                ]}""");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsUnknownTier() throws Exception {
        Path file = writeJson("""
                {"examples": [
                  {"id": "a", "tier": "TIER_9", "language": "ENGLISH", "tags": [], "text": "hi"}
                ]}""");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tier");
    }

    @Test
    void rejectsUnknownLanguage() throws Exception {
        Path file = writeJson("""
                {"examples": [
                  {"id": "a", "tier": "TIER_1", "language": "KLINGON", "tags": [], "text": "hi"}
                ]}""");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown language");
    }

    @Test
    void rejectsBlankText() throws Exception {
        Path file = writeJson("""
                {"examples": [
                  {"id": "a", "tier": "TIER_1", "language": "ENGLISH", "tags": [], "text": "   "}
                ]}""");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing id/tier/language/text");
    }

    @Test
    void rejectsDatasetMissingATier() throws Exception {
        Path file = writeJson("""
                {"examples": [
                  {"id": "a", "tier": "TIER_1", "language": "ENGLISH", "tags": [], "text": "hi"},
                  {"id": "b", "tier": "TIER_1", "language": "URDU", "tags": [], "text": "salam"}
                ]}""");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every tier");
    }

    @Test
    void rejectsEmptyExamplesArray() throws Exception {
        Path file = writeJson("{\"examples\": []}");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void missingFileFailsWithActionableError() {
        assertThatThrownBy(() -> loader.load("classpath:routing/does-not-exist.json"))
                .hasMessageContaining("Cannot read routing examples");
    }

    private Path writeJson(String json) throws Exception {
        Path file = tempDir.resolve("examples.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
