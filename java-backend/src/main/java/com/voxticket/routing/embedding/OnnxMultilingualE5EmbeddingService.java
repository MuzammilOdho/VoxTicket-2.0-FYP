package com.voxticket.routing.embedding;

import ai.onnxruntime.*;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingInferenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2: production {@link RoutingEmbeddingService} running
 * {@code intfloat/multilingual-e5-small} locally via ONNX Runtime.
 *
 * <p>Pipeline per call: e5 {@code "query: "} prefix &rarr; {@link E5UnigramTokenizer} &rarr;
 * ONNX {@code input_ids} + {@code attention_mask} &rarr; {@code last_hidden_state} &rarr;
 * attention-mask-aware mean pooling &rarr; L2 normalization &rarr; 384-d vector.
 *
 * <p>Fully local: no Python, no Ollama, no network, no pgvector, no downloads at runtime.
 * The model directory is fixed configuration; a missing or unreadable directory fails fast
 * with an actionable error (HYBRID startup must fail clearly, never silently degrade).
 *
 * <p>Thread-safe: inference calls are serialized on the session.
 */
public class OnnxMultilingualE5EmbeddingService implements RoutingEmbeddingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxMultilingualE5EmbeddingService.class);

    /** e5 retrieval models require the {@code "query: "} prefix on queries (never on documents). */
    private static final String QUERY_PREFIX = "query: ";
    private static final String MODEL_FILE = "model.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";

    private final E5UnigramTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputIdsName;
    private final String attentionMaskName;
    /** Null when the graph does not take token-type ids (some exports do, e.g. e5). */
    private final String tokenTypeIdsName;
    private final String outputName;
    private final String modelSha256;

    /**
     * @param modelDir directory containing {@code model.onnx} and {@code tokenizer.json}.
     * @throws IllegalStateException if the directory or either artifact is missing/unreadable,
     *         or the ONNX graph does not expose the expected inputs/outputs.
     */
    public OnnxMultilingualE5EmbeddingService(Path modelDir) {
        Path modelPath = requireArtifact(modelDir, MODEL_FILE);
        Path tokenizerPath = requireArtifact(modelDir, TOKENIZER_FILE);
        this.modelSha256 = sha256(modelPath);
        this.tokenizer = E5UnigramTokenizer.load(tokenizerPath);
        try {
            this.environment = OrtEnvironment.getEnvironment("voxticket-routing");
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
            this.session = environment.createSession(modelPath.toString(), options);
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to load routing ONNX model from " + modelPath, e);
        }
        try {
            Set<String> inputs = session.getInputNames();
            this.inputIdsName = pickInput(inputs, "input_ids");
            this.attentionMaskName = pickInput(inputs, "attention_mask");
            this.tokenTypeIdsName = pickOptionalInput(inputs, "token_type_ids");
            this.outputName = pickOutput(session.getOutputInfo().keySet());
            log.info("event=routing_model_loaded model=intfloat/multilingual-e5-small dim={} sha256={} path={}",
                    DIMENSION, modelSha256, modelPath);
        } catch (OrtException e) {
            closeQuietly();
            throw new IllegalStateException("Failed to inspect routing ONNX model inputs/outputs", e);
        }
    }

    @Override
    public float[] embed(String text) {
        String raw = text == null ? "" : text;
        int[] ids = tokenizer.encode(QUERY_PREFIX + raw);
        int seq = ids.length;
        long[][] inputIds = new long[1][seq];
        long[][] attentionMask = new long[1][seq];
        for (int i = 0; i < seq; i++) {
            inputIds[0][i] = ids[i];
            attentionMask[0][i] = 1L;
        }
        Map<String, OnnxTensor> inputs = Map.of();
        try {
            Map<String, OnnxTensor> feeds = new java.util.HashMap<>();
            feeds.put(inputIdsName, OnnxTensor.createTensor(environment, inputIds));
            feeds.put(attentionMaskName, OnnxTensor.createTensor(environment, attentionMask));
            if (tokenTypeIdsName != null) {
                // Single-segment input: all token types are 0.
                feeds.put(tokenTypeIdsName, OnnxTensor.createTensor(environment, new long[1][seq]));
            }
            inputs = feeds;
            float[] pooled;
            synchronized (session) {
                try (OrtSession.Result result = session.run(inputs)) {
                    OnnxTensor hiddenState = (OnnxTensor) result.get(outputName)
                            .orElseThrow(() -> new RoutingInferenceException(
                                    "Routing model returned no '" + outputName + "' output"));
                    pooled = maskedMeanPool(hiddenState, seq);
                }
            }
            return l2Normalize(pooled);
        } catch (OrtException e) {
            throw new RoutingInferenceException("Routing embedding inference failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    /**
     * Attention-mask-aware mean pooling over {@code [1][seq][384]} last-hidden-state.
     * (The mask is all ones here since we never pad, but the pooling is written against the
     * mask so a future batched/padded path cannot silently skew vectors.)
     */
    private static float[] maskedMeanPool(OnnxTensor hiddenState, int seq) {
        long[] shape = hiddenState.getInfo().getShape();
        if (shape.length != 3 || shape[0] != 1 || shape[1] != seq || shape[2] != DIMENSION) {
            throw new RoutingInferenceException("Unexpected routing model output shape "
                    + java.util.Arrays.toString(shape) + ", expected [1][" + seq + "][" + DIMENSION + "]");
        }
        FloatBuffer buffer = hiddenState.getFloatBuffer();
        float[] pooled = new float[DIMENSION];
        for (int i = 0; i < seq; i++) {
            int base = i * DIMENSION;
            for (int j = 0; j < DIMENSION; j++) {
                pooled[j] += buffer.get(base + j);
            }
        }
        for (int j = 0; j < DIMENSION; j++) {
            pooled[j] /= seq;
        }
        return pooled;
    }

    private static float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-12) {
            throw new RoutingInferenceException("Routing model returned a near-zero vector");
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private static Path requireArtifact(Path modelDir, String fileName) {
        if (modelDir == null) {
            throw new IllegalStateException("Routing model directory is not configured: set "
                    + "voxticket.ai.selector.semantic.model-path (env ROUTING_MODEL_PATH) to a directory "
                    + "containing " + MODEL_FILE + " and " + TOKENIZER_FILE);
        }
        Path path = modelDir.resolve(fileName);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Routing model artifact missing or unreadable: " + path
                    + " - download intfloat/multilingual-e5-small (ONNX) into the configured "
                    + "voxticket.ai.selector.semantic.model-path directory");
        }
        return path;
    }

    private static String pickOptionalInput(Set<String> inputs, String wanted) {
        return inputs.stream()
                .filter(name -> name.equals(wanted) || name.endsWith(":" + wanted) || name.contains(wanted))
                .findFirst()
                .orElse(null);
    }

    private static String pickInput(Set<String> inputs, String wanted) {
        String found = pickOptionalInput(inputs, wanted);
        if (found == null) {
            throw new IllegalStateException("Routing ONNX model has no '"
                    + wanted + "' input; found " + inputs);
        }
        return found;
    }

    private static String pickOutput(Set<String> outputs) {
        return outputs.stream()
                .filter(name -> name.contains("last_hidden_state"))
                .findFirst()
                .or(() -> outputs.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("Routing ONNX model has no outputs"));
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                din.readAllBytes();
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Cannot hash routing model file " + path, e);
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception e) {
            log.warn("event=routing_model_close_failed error={}", e.toString());
        }
        try {
            if (environment != null) {
                environment.close();
            }
        } catch (Exception e) {
            log.warn("event=routing_env_close_failed error={}", e.toString());
        }
    }
}
