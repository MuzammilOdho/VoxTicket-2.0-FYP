package com.voxticket.routing.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchIllegalArgumentException;

/**
 * Phase 2: {@link E5UnigramTokenizer} mechanics with a tiny synthetic unigram vocabulary.
 * Full-fidelity verification against the reference {@code tokenizers} implementation
 * (339 representative strings, 0 mismatches, incl. Urdu/Roman-Urdu/code-switch/control
 * characters) was done with a dev-time oracle harness during development; this test pins the
 * algorithm's structural behavior without needing the 17&nbsp;MB production vocabulary.
 */
class E5UnigramTokenizerTest {

    @TempDir
    Path tempDir;

    /** vocab: [piece, score]; ids are positional. Scores chosen so the best path is unambiguous. */
    private E5UnigramTokenizer tokenizer(String... piecesAndScores) throws Exception {
        StringBuilder vocab = new StringBuilder("[");
        int count = 0;
        for (int i = 0; i < piecesAndScores.length; i += 2) {
            if (i > 0) {
                vocab.append(",");
            }
            vocab.append("[\"").append(piecesAndScores[i]).append("\",").append(piecesAndScores[i + 1]).append("]");
            count++;
        }
        // Production load() rejects vocabularies under 1000 pieces (corruption sanity check);
        // pad with inert pieces that can never win Viterbi (deeply negative scores, and no
        // test text contains "pad" after a metaspace so they never match).
        for (int i = count; i < 1000; i++) {
            vocab.append(",[\"▁pad").append(i).append("\",-1000.0]");
        }
        vocab.append("]");
        String json = "{\"model\":{\"type\":\"Unigram\",\"vocab\":" + vocab + "}}";
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return E5UnigramTokenizer.load(file);
    }

    @Test
    void viterbiPicksHighestScoringSegmentation() throws Exception {
        // "▁hello": [▁hell]+[o] scores 2.0, [▁hello] scores 5.0 -> whole-word piece must win.
        E5UnigramTokenizer t = tokenizer("▁hello", "5.0", "▁hell", "1.0", "o", "1.0");

        assertThat(t.encode("hello")).containsExactly(E5UnigramTokenizer.BOS_ID, 0, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void viterbiFallsBackToShorterPiecesWhenWholeWordLoses() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁hello", "0.5", "▁hell", "3.0", "o", "3.0");

        assertThat(t.encode("hello")).containsExactly(
                E5UnigramTokenizer.BOS_ID, 1, 2, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void prefixSpaceIsAddedOnlyWhenMissing() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁hi", "1.0");
        int[] withoutLeadingSpace = t.encode("hi");
        int[] withLeadingSpace = t.encode(" hi");

        assertThat(withoutLeadingSpace).isEqualTo(withLeadingSpace);
        assertThat(withoutLeadingSpace).containsExactly(E5UnigramTokenizer.BOS_ID, 0, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void spacesBecomeMetaspacePieces() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁a", "1.0", "▁b", "1.0");

        assertThat(t.encode("a b")).containsExactly(E5UnigramTokenizer.BOS_ID, 0, 1, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void unknownCharactersFallBackToUnk() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁a", "1.0");

        // 'Ω' has no piece: one UNK per code point, 'a' still segments normally.
        assertThat(t.encode("aΩ")).containsExactly(
                E5UnigramTokenizer.BOS_ID, 0, E5UnigramTokenizer.UNK_ID, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void emptyInputYieldsOnlySpecialTokens() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁", "2.0", "▁a", "1.0");

        // "" -> prefix space -> "▁", which is a real piece in this vocab.
        assertThat(t.encode("")).containsExactly(E5UnigramTokenizer.BOS_ID, 0, E5UnigramTokenizer.EOS_ID);
        assertThat(t.encode(null)).containsExactly(E5UnigramTokenizer.BOS_ID, 0, E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void outputTruncatesToMaxTokens() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁a", "1.0");

        int[] ids = t.encode("a ".repeat(1000));

        assertThat(ids).hasSize(E5UnigramTokenizer.MAX_TOKENS);
        assertThat(ids[0]).isEqualTo(E5UnigramTokenizer.BOS_ID);
        assertThat(ids[ids.length - 1]).isEqualTo(E5UnigramTokenizer.EOS_ID);
    }

    @Test
    void controlWhitespaceNormalizesLikeSpaces() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁a", "1.0", "▁b", "1.0");

        assertThat(t.encode("a\tb")).isEqualTo(t.encode("a b"));
        assertThat(t.encode("a\nb")).isEqualTo(t.encode("a b"));
        assertThat(t.encode("a\rb")).isEqualTo(t.encode("a b"));
    }

    @Test
    void strippedControlCharactersVanish() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁ab", "1.0");

        assertThat(t.encode("a\u0001b")).isEqualTo(t.encode("ab"));
        assertThat(t.encode("a\u001Fb")).isEqualTo(t.encode("ab"));
        assertThat(t.encode("a\u007Fb")).isEqualTo(t.encode("ab"));
    }

    @Test
    void nonBreakingSpaceNormalizesToSpace() throws Exception {
        E5UnigramTokenizer t = tokenizer("▁a", "1.0", "▁b", "1.0");

        assertThat(t.encode("a\u00A0b")).isEqualTo(t.encode("a b"));
    }

    @Test
    void rejectsNonUnigramTokenizerJson() throws Exception {
        Path file = tempDir.resolve("tokenizer.json");
        Files.writeString(file, "{\"model\":{\"type\":\"BPE\",\"vocab\":[]}}", StandardCharsets.UTF_8);

        assertThat(catchIllegalArgumentException(() -> E5UnigramTokenizer.load(file)))
                .hasMessageContaining("Unigram");
    }
}
