package com.voxticket.routing.embedding;

import com.voxticket.embedding.VoyageEmbeddingClient;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingInferenceException;
import java.util.List;

/**
 * Routing embeddings from Voyage, for the cloud provider only.
 *
 * <p>Routing is classification, not retrieval: prototypes are example
 * user messages and the live input is a user message, so both sides
 * use {@code input_type=query} - the same space on both sides, which is
 * what cosine scoring against prototypes needs. (RAG, by contrast, uses
 * {@code document} for stored text and {@code query} for searches; see
 * VoyageEmbeddingModel.) Vectors are L2-normalized here so
 * RoutingExampleIndex can score them with plain dot products, exactly
 * as it does for ONNX vectors.
 *
 * <p>Startup cost: RoutingExampleIndex calls {@link #embedBatch} once
 * for all 96 prototypes, so startup is one Voyage call, not 96. Per
 * turn cost: exactly one Voyage call, and only when no structural
 * signal short-circuited the turn (see SemanticRoutingService).
 */
public class VoyageRoutingEmbeddingService implements RoutingEmbeddingService {

    private final VoyageEmbeddingClient client;

    public VoyageRoutingEmbeddingService(VoyageEmbeddingClient client) {
        if (client == null) {
            throw new IllegalArgumentException("VoyageEmbeddingClient must not be null");
        }
        this.client = client;
    }

    @Override
    public int dimension() {
        return VoyageEmbeddingClient.EXPECTED_DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        try {
            float[] vector = client.embed(text == null ? "" : text, VoyageEmbeddingClient.InputType.QUERY);
            return l2Normalize(vector);
        } catch (Exception e) {
            if (e instanceof RoutingInferenceException) {
                throw (RoutingInferenceException) e;
            }
            throw new RoutingInferenceException("Voyage routing embedding failed", e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            return client.embed(texts, VoyageEmbeddingClient.InputType.QUERY).stream()
                    .map(VoyageRoutingEmbeddingService::l2Normalize)
                    .toList();
        } catch (Exception e) {
            if (e instanceof RoutingInferenceException) {
                throw (RoutingInferenceException) e;
            }
            throw new RoutingInferenceException("Voyage routing embedding batch failed", e);
        }
    }

    private static float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            throw new RoutingInferenceException("Voyage routing embedding is a zero vector");
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
