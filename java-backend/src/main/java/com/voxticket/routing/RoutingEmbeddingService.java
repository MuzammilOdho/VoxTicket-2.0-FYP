package com.voxticket.routing;

/**
 * Phase 2: local routing embeddings.
 *
 * <p>The production implementation ({@code OnnxMultilingualE5EmbeddingService}) runs
 * {@code intfloat/multilingual-e5-small} via ONNX Runtime entirely in the JVM: no Python, no
 * Ollama, no network, no pgvector, and completely separate from the RAG {@code bge-m3}
 * embeddings (1024-d, pgvector).
 *
 * <p>Contract:
 * <ul>
 *   <li>input is the RAW text - the implementation applies the e5 {@code "query: "} prefix
 *       itself, so stored prototypes and live queries are embedded identically;</li>
 *   <li>only the current user message is ever embedded - never full conversation history;</li>
 *   <li>output is the L2-normalized 384-dimensional vector, so cosine similarity is a dot
 *       product;</li>
 *   <li>at most one {@code embed} call happens per routed turn
 *       ({@code SemanticRoutingService} embeds the query once; prototype vectors are cached
 *       at startup and never recomputed).</li>
 * </ul>
 */
public interface RoutingEmbeddingService {

    /** Embedding dimension of the routing model (multilingual-e5-small: 384). */
    int DIMENSION = 384;

    /**
     * Embeds raw text and returns the L2-normalized embedding vector of length {@link #DIMENSION}.
     *
     * @throws RoutingInferenceException if inference fails; callers must NOT silently fall back
     *         to another strategy - a routing failure must stay observable.
     */
    float[] embed(String text);
}
