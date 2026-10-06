package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.agent.ModelTier;
import com.voxticket.routing.RoutingExample;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3: corpus integrity regression tests. Hermetic - no ONNX model needed.
 * Any violation fails the build: the evaluation corpus must stay balanced and
 * leakage-free.
 */
class EvalCorpusIntegrityTest {

    private static final Path CORPUS_DIR = Paths.get("evaluation/routing/corpus");

    private List<EvalExample> loadAll() {
        List<EvalExample> all = new java.util.ArrayList<>(EvalCorpus.load(CORPUS_DIR.resolve("validation.json")));
        all.addAll(EvalCorpus.load(CORPUS_DIR.resolve("final-test.json")));
        return all;
    }

    private Set<String> prototypeTexts() {
        try {
            tools.jackson.databind.JsonNode root = new JsonMapper().readTree(
                    Paths.get("src/main/resources/routing/examples-v1.json").toFile());
            List<RoutingExample> prototypes = new JsonMapper().treeToValue(
                    root.get("examples"),
                    new JsonMapper().getTypeFactory().constructCollectionType(
                            List.class, RoutingExample.class));
            Set<String> texts = new HashSet<>();
            for (RoutingExample prototype : prototypes) {
                texts.add(normalize(prototype.text()));
            }
            assertThat(texts).hasSize(96);
            return texts;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load routing prototypes", e);
        }
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Test
    void corpusHasNoIntegrityViolations() {
        List<String> violations = EvalCorpus.validate(loadAll(), prototypeTexts());
        assertThat(violations).as("corpus integrity violations: %s", violations).isEmpty();
    }

    @Test
    void exactCorpusSize() {
        List<EvalExample> validation = EvalCorpus.load(CORPUS_DIR.resolve("validation.json"));
        List<EvalExample> finalTest = EvalCorpus.load(CORPUS_DIR.resolve("final-test.json"));
        assertThat(validation).hasSize(120);
        assertThat(finalTest).hasSize(120);
        assertThat(validation).allMatch(e -> e.split() == EvalSplit.VALIDATION);
        assertThat(finalTest).allMatch(e -> e.split() == EvalSplit.FINAL_TEST);
    }

    @Test
    void exactLanguageAndTierBalance() {
        for (Path file : List.of(CORPUS_DIR.resolve("validation.json"), CORPUS_DIR.resolve("final-test.json"))) {
            List<EvalExample> examples = EvalCorpus.load(file);
            for (EvalLanguage language : EvalLanguage.values()) {
                for (ModelTier tier : ModelTier.values()) {
                    long count = examples.stream()
                            .filter(e -> e.language() == language && e.expectedTier() == tier)
                            .count();
                    assertThat(count)
                            .as("%s %s %s", file.getFileName(), language, tier)
                            .isEqualTo(15);
                }
            }
        }
    }

    @Test
    void noDuplicateIdsOrTexts() {
        List<EvalExample> all = loadAll();
        assertThat(all.stream().map(EvalExample::id)).doesNotHaveDuplicates();
        assertThat(all.stream().map(EvalExample::normalizedText)).doesNotHaveDuplicates();
    }

    @Test
    void noValidationTestLeakage() {
        Set<String> validationTexts = new HashSet<>();
        for (EvalExample e : EvalCorpus.load(CORPUS_DIR.resolve("validation.json"))) {
            validationTexts.add(e.normalizedText());
        }
        for (EvalExample e : EvalCorpus.load(CORPUS_DIR.resolve("final-test.json"))) {
            assertThat(validationTexts)
                    .as("final-test overlaps validation: " + e.id())
                    .doesNotContain(e.normalizedText());
        }
    }

    @Test
    void noExactPrototypeOverlap() {
        Set<String> prototypes = prototypeTexts();
        for (EvalExample e : loadAll()) {
            assertThat(prototypes)
                    .as("evaluation text overlaps a prototype: " + e.id())
                    .doesNotContain(e.normalizedText());
        }
    }

    @Test
    void noBlankTextOrRationale() {
        for (EvalExample e : loadAll()) {
            assertThat(e.text()).as(e.id() + " text").isNotBlank();
            assertThat(e.sourceNote()).as(e.id() + " sourceNote").isNotBlank();
            assertThat(e.tags()).as(e.id() + " tags").isNotEmpty();
        }
    }

    @Test
    void tagsUseControlledVocabulary() {
        for (EvalExample e : loadAll()) {
            assertThat(EvalCorpus.CONTROLLED_TAGS)
                    .as(e.id() + " tags")
                    .containsAll(e.tags());
        }
    }
}
