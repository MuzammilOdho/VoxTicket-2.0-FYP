package com.voxticket.routing.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2: pins exact token-id sequences of the production
 * {@code intfloat/multilingual-e5-small} vocabulary.
 *
 * <p>Runs only when {@code -Dvoxticket.routing.tokenizer.path=/path/to/tokenizer.json} is
 * given (the 17&nbsp;MB vocabulary is a local artifact, never committed). The expected ids
 * below were captured from the verified implementation and cross-checked token-for-token
 * against the reference {@code tokenizers} implementation.
 */
@EnabledIfSystemProperty(named = "voxticket.routing.tokenizer.path", matches = ".+")
class E5TokenizerKnownIdsTest {

    private static E5UnigramTokenizer tokenizer;

    @BeforeAll
    static void loadProductionVocabulary() {
        Path path = Paths.get(System.getProperty("voxticket.routing.tokenizer.path"));
        tokenizer = E5UnigramTokenizer.load(path);
    }

    @Test
    void englishSimpleQuery() {
        assertThat(tokenizer.encode("Where is my order?"))
                .containsExactly(0, 78662, 83, 759, 12989, 32, 2);
    }

    @Test
    void englishGreeting() {
        assertThat(tokenizer.encode("Hello")).containsExactly(0, 35378, 2);
    }

    @Test
    void orderReference() {
        assertThat(tokenizer.encode("ORD-10010"))
                .containsExactly(0, 6, 36639, 45881, 963, 2);
    }

    @Test
    void urduSimpleQuery() {
        assertThat(tokenizer.encode("میرا آرڈر کہاں ہے؟"))
                .containsExactly(0, 63291, 8523, 74439, 117993, 639, 1245, 2);
    }

    @Test
    void romanUrduSimpleQuery() {
        assertThat(tokenizer.encode("Mera order kahan hai?"))
                .containsExactly(0, 40758, 12989, 156, 1121, 1337, 32, 2);
    }

    @Test
    void codeSwitchQuery() {
        assertThat(tokenizer.encode("Mera order kahan hai? Order number ORD-10040 hai"))
                .containsExactly(0, 40758, 12989, 156, 1121, 1337, 32, 81206, 14012, 6, 36639, 45881, 2839, 1337, 2);
    }

    @Test
    void englishMultiOrderQuery() {
        assertThat(tokenizer.encode("Cancel ORD-10050 and ORD-10051"))
                .containsExactly(0, 80392, 6, 36639, 45881, 2525, 136, 6, 36639, 45881, 11703, 2);
    }

    @Test
    void shortWord() {
        assertThat(tokenizer.encode("hi")).containsExactly(0, 1274, 2);
    }
}
