package com.voxticket.routing.embedding;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 2: pure-JVM SentencePiece-Unigram tokenizer for {@code intfloat/multilingual-e5-small}.
 *
 * <p>Replicates the HuggingFace {@code tokenizer.json} pipeline exactly:
 * <ol>
 *   <li>normalization (NFKC + the precompiled control/space handling + collapsing 2+ spaces),</li>
 *   <li>Metaspace pre-tokenization ({@code ' '} &rarr; {@code '▁'}, {@code add_prefix_space=true}),</li>
 *   <li>unigram Viterbi segmentation over the 250k-piece vocabulary,</li>
 *   <li>{@code <s>} / {@code </s>} special tokens, truncated to 512 tokens.</li>
 * </ol>
 *
 * <p>Verified token-for-token against the reference {@code tokenizers} implementation during
 * development (see the dev-time oracle harness, not shipped). No native code, no Python.
 *
 * <p>Thread-safe after construction; construction parses the 17&nbsp;MB {@code tokenizer.json}
 * once (callers should share a single instance).
 */
public final class E5UnigramTokenizer {

    /** Maximum sequence length of the e5 model ({@code max_position_embeddings}). */
    public static final int MAX_TOKENS = 512;
    /** Token id of {@code <s>}. */
    public static final int BOS_ID = 0;
    /** Token id of {@code </s>}. */
    public static final int EOS_ID = 2;
    /** Token id of {@code <unk>}. */
    public static final int UNK_ID = 3;

    /** Metaspace replacement character (U+2581). */
    private static final char METASPACE = '\u2581';
    /** Score so low the UNK fallback only wins when no vocabulary piece matches. */
    private static final double UNK_FALLBACK_SCORE = -1e9;
    private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");

    private final Map<String, PieceScore> vocab;
    private final int maxPieceChars;

    private E5UnigramTokenizer(Map<String, PieceScore> vocab, int maxPieceChars) {
        this.vocab = vocab;
        this.maxPieceChars = maxPieceChars;
    }

    private record PieceScore(int id, double score) {
    }

    /**
     * Loads the tokenizer from a HuggingFace {@code tokenizer.json} (unigram model section).
     *
     * @throws IllegalArgumentException if the file is not a unigram {@code tokenizer.json}.
     */
    public static E5UnigramTokenizer load(Path tokenizerJson) {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode root;
        try {
            root = mapper.readTree(tokenizerJson);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Cannot read routing tokenizer.json: " + tokenizerJson, e);
        }
        JsonNode model = root.path("model");
        if (!"Unigram".equals(model.path("type").asText())) {
            throw new IllegalArgumentException("Expected a Unigram tokenizer.json but found type '"
                    + model.path("type").asText() + "' in " + tokenizerJson);
        }
        Map<String, PieceScore> vocab = new HashMap<>(300_000);
        int maxPieceChars = 0;
        int id = 0;
        for (JsonNode entry : model.path("vocab")) {
            String piece = entry.get(0).asText();
            double score = entry.get(1).asDouble();
            vocab.put(piece, new PieceScore(id, score));
            maxPieceChars = Math.max(maxPieceChars, piece.length());
            id++;
        }
        if (vocab.size() < 1000) {
            throw new IllegalArgumentException("Suspiciously small unigram vocabulary (" + vocab.size()
                    + " pieces) in " + tokenizerJson);
        }
        return new E5UnigramTokenizer(vocab, maxPieceChars);
    }

    /**
     * Tokenizes exactly the given text: normalization, metaspace pre-tokenization, unigram
     * Viterbi segmentation, then {@code <s>} ... {@code </s>} with truncation to
     * {@link #MAX_TOKENS} total tokens.
     *
     * <p>The e5 {@code "query: "} prefix is NOT added here - that is the embedding service's
     * job ({@code OnnxMultilingualE5EmbeddingService}).
     */
    public int[] encode(String text) {
        String normalized = normalize(text == null ? "" : text);
        StringBuilder pre = new StringBuilder(normalized.length() + 1);
        String spaced = (!normalized.startsWith(" ") && !normalized.startsWith("▁"))
                ? " " + normalized
                : normalized;
        for (int i = 0; i < spaced.length(); i++) {
            char c = spaced.charAt(i);
            pre.append(c == ' ' ? METASPACE : c);
        }
        List<Integer> pieces = viterbi(pre.toString());
        int body = Math.min(pieces.size(), MAX_TOKENS - 2);
        int[] ids = new int[body + 2];
        ids[0] = BOS_ID;
        for (int i = 0; i < body; i++) {
            ids[i + 1] = pieces.get(i);
        }
        ids[body + 1] = EOS_ID;
        return ids;
    }

    /**
     * Replicates the {@code tokenizer.json} normalizer sequence: NFKC, then the precompiled
     * control/space handling, then collapsing of 2+ spaces.
     *
     * <p>The stripped/mapped sets below were reverse-engineered from the reference
     * {@code tokenizers} implementation by probing every BMP codepoint (plus astral samples):
     * 30 control characters are stripped, and tab/newline/form-feed/carriage-return plus the
     * Unicode space separators (Zs/Zl/Zp) and a small set of format characters are mapped to a
     * plain space. Everything else - including NUL and the C1 controls - is kept.
     */
    private static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (isStripped(c)) {
                continue;
            }
            out.append(isSpaceMapped(c) ? ' ' : c);
        }
        return MULTI_SPACE.matcher(out).replaceAll(" ");
    }

    private static boolean isStripped(char c) {
        // Reverse-engineered from the reference tokenizer: exactly these 30
        // control characters are dropped by the precompiled normalizer.
        return (c >= '\u0001' && c <= '\u0008')
                || c == '\u000B'
                || (c >= '\u000E' && c <= '\u001F')
                || c == '\u007F'
                || c == '\u008F'
                || c == '\u009F';
    }

    private static boolean isSpaceMapped(char c) {
        // Reverse-engineered from the reference tokenizer: tab/newline/
        // form-feed/carriage-return, the Unicode space separators (Zs/Zl/Zp)
        // and a small set of format characters become a plain space.
        // (U+0020 needs no mapping; the metaspace step handles it.)
        return c == '\t' || c == '\n' || c == '\f' || c == '\r'
                || c == '\u00A0' || c == '\u1680'
                || (c >= '\u2000' && c <= '\u200A')
                || c == '\u200B' || c == '\u200C' || c == '\u200D'
                || c == '\u200E' || c == '\u200F'
                || c == '\u2028' || c == '\u2029'
                || c == '\u202F' || c == '\u205F'
                || c == '\u2581' || c == '\u3000'
                || c == '\uFEFF' || c == '\uFFFD';
    }

    /**
     * Unigram Viterbi segmentation. Unknown characters (no vocabulary piece matches) fall back
     * to {@code <unk>} for one code point - with this 250k vocabulary that path is effectively
     * unreachable for real input, but it keeps the pipeline total.
     */
    private List<Integer> viterbi(String s) {
        int n = s.length();
        double[] best = new double[n + 1];
        int[] backId = new int[n + 1];
        int[] backPos = new int[n + 1];
        Arrays.fill(best, Double.NEGATIVE_INFINITY);
        Arrays.fill(backPos, -1);
        best[0] = 0.0;

        for (int i = 0; i < n; i++) {
            if (Double.isInfinite(best[i])) {
                continue;
            }
            int limit = Math.min(maxPieceChars, n - i);
            for (int len = 1; len <= limit; len++) {
                PieceScore ps = vocab.get(s.substring(i, i + len));
                if (ps != null) {
                    double candidate = best[i] + ps.score();
                    if (candidate > best[i + len]) {
                        best[i + len] = candidate;
                        backId[i + len] = ps.id();
                        backPos[i + len] = i;
                    }
                }
            }
            int cpLen = Character.charCount(s.codePointAt(i));
            double unkCandidate = best[i] + UNK_FALLBACK_SCORE;
            if (unkCandidate > best[i + cpLen]) {
                best[i + cpLen] = unkCandidate;
                backId[i + cpLen] = UNK_ID;
                backPos[i + cpLen] = i;
            }
        }

        List<Integer> ids = new ArrayList<>();
        int p = n;
        while (p > 0) {
            int prev = backPos[p];
            if (prev < 0 || prev >= p) {
                // Defensive: unreachable in practice (UNK fallback keeps the lattice total).
                return List.of(UNK_ID);
            }
            ids.add(backId[p]);
            p = prev;
        }
        Collections.reverse(ids);
        return ids;
    }
}
