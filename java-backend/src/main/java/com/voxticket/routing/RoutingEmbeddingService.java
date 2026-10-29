package com.voxticket.routing;

/**
 * Routing embeddings, local or cloud.
 *
 * <p>Local implementation ({@code OnnxMultilingualE5EmbeddingService}) runs
 * {@code intfloat/multilingual-e5-small} via ONNX Runtime entirely in the JVM: no Python, no
 * Ollama, no network, no pgvector, and completely separate from the RAG {@code bge-m3}
 * embeddings (1024-d, pgvector). Cloud implementation
 * ({@code VoyageRoutingEmbeddingService}) uses Voyage voyage-4-lite (1024-d)
 * for both routing and RAG - see {@code EmbeddingProperties}.
 *
 * <p>Contract:
 * <ul>
 *   <li>input is the RAW text - each implementation applies its own
 *       query-side handling itself (e5 {@code "query: "} prefix, or
 *       Voyage {@code input_type=query}), so stored prototypes and live
 *       queries are embedded identically;</li>
 *   <li>only the current user message is ever embedded - never full conversation history;</li>
 *   <li>output is the L2-normalized vector of length {@link #dimension()},
 *       so cosine similarity is a dot product;</li>
 *   <li>at most one {@code embed} call happens per routed turn
 *       ({@code SemanticRoutingService} embeds the query once; prototype vectors are cached
 *       at startup and never recomputed).</li>
 * </ul>
 */
public interface RoutingEmbeddingService {

    /** Legacy ONNX dimension (multilingual-e5-small: 384). Kept for the ONNX service and existing tests; new code must use {@link #dimension()}. */
    int DIMENSION = 384;

    /** Dimension of vectors this service produces (384 local ONNX, 1024 Voyage). */
    default int dimension() {
        return DIMENSION;
    }

    /**
     * Embeds raw text and returns the L2-normalized embedding vector of length {@link #dimension()}.
     *
     * @throws RoutingInferenceException if inference fails; callers must NOT silently fall back
     *         to another strategy - a routing failure must stay observable.
     */
    float[] embed(String text);

    /**
     * Batch embedding, in input order. Default loops {@link #embed} (ONNX
     * behaviour, unchanged). Voyage overrides it with a single HTTP call
     * for all 96 startup prototypes, so cloud startup is not 96 round-trips.
     */
    default java.util.List<float[]> embedBatch(java.util.List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return java.util.List.of();
        }
        return texts.stream().map(this::embed).toList();
    }
}
