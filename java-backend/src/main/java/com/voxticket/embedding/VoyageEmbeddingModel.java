package com.voxticket.embedding;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Spring AI {@link org.springframework.ai.embedding.EmbeddingModel} backed
 * by Voyage, used for RAG only. The routing path uses
 * {@link com.voxticket.routing.embedding.VoyageRoutingEmbeddingService}
 * directly, so the two uses do not share Spring AI plumbing, and they do
 * not share a client either: routing builds its own
 * {@link VoyageEmbeddingClient} from the same properties (see
 * RoutingConfiguration). The two clients share the timeout and the
 * retry rule, but not a connection pool.
 *
 * <p>How the three VectorStore paths map to Voyage {@code input_type}:
 * <ul>
 *   <li>{@link #call(EmbeddingRequest)} is the query path (VectorStore
 *       similarity search embeds the query through it): {@code query}.
 *       RagService prepends a bge-m3 instruction
 *       ("Represent this sentence for searching relevant passages: ")
 *       to queries; that prefix is stripped here, because for Voyage
 *       the {@code query} input type already carries that intent, and
 *       sending a bge-m3 instruction to a Voyage model would skew the
 *       vector for no benefit.</li>
 *   <li>{@link #embed(Document)} is the single-document path (VectorStore
 *       add of one document embeds it through it): {@code document}.</li>
 *   <li>{@link #embed(List, EmbeddingOptions, BatchingStrategy)} is the
 *       batched-document path (VectorStore add of a list embeds it
 *       through it): {@code document} for every document in the batch.
 *       This is the path KnowledgeIngestionService now uses, so a
 *       7-document migration is one Voyage call (or one call per
 *       batching-strategy sub-batch), not 7 calls. The batching
 *       strategy is honoured: its sub-batches are embedded separately,
 *       in order. A null strategy means one batch for the whole list.
 *       The {@code options} argument is ignored: Voyage model,
 *       dimension, and input type are fixed by this model, not
 *       per-request.</li>
 * </ul>
 * {@link #dimensions()} is fixed at 1024 so PgVectorStore never has to
 * make a live Voyage call just to learn the dimension at startup.
 */
public class VoyageEmbeddingModel extends AbstractEmbeddingModel {

    /** Mirrors RagService.QUERY_INSTRUCTION (package-private there); stripped for Voyage, see class doc. */
    static final String BGE_QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: ";

    /**
     * Voyage's maximum list length for one embeddings request
     * (docs.voyageai.com embeddings page). A batching-strategy sub-batch
     * larger than this is split here so one oversized batch cannot fail
     * the whole sync with a 400.
     */
    static final int MAX_BATCH_INPUTS = 1000;

    private final VoyageEmbeddingClient client;

    public VoyageEmbeddingModel(VoyageEmbeddingClient client) {
        this.client = client;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        List<String> queries = texts.stream()
                .map(t -> t == null ? "" : t)
                .map(t -> t.startsWith(BGE_QUERY_INSTRUCTION)
                        ? t.substring(BGE_QUERY_INSTRUCTION.length()) : t)
                .toList();
        List<float[]> vectors = client.embed(queries, VoyageEmbeddingClient.InputType.QUERY);
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        String text = document == null || document.getText() == null ? "" : document.getText();
        return client.embed(text, VoyageEmbeddingClient.InputType.DOCUMENT);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<List<Document>> batches;
        if (batchingStrategy == null) {
            batches = List.of(documents);
        } else {
            batches = batchingStrategy.batch(documents);
            if (batches == null) {
                batches = List.of(documents);
            }
        }
        List<float[]> result = new ArrayList<>();
        for (List<Document> batch : batches) {
            if (batch == null || batch.isEmpty()) {
                continue;
            }
            for (int start = 0; start < batch.size(); start += MAX_BATCH_INPUTS) {
                List<Document> chunk = batch.subList(start, Math.min(start + MAX_BATCH_INPUTS, batch.size()));
                List<String> texts = chunk.stream()
                        .map(d -> d == null || d.getText() == null ? "" : d.getText())
                        .toList();
                result.addAll(client.embed(texts, VoyageEmbeddingClient.InputType.DOCUMENT));
            }
        }
        return result;
    }

    @Override
    public int dimensions() {
        return VoyageEmbeddingClient.EXPECTED_DIMENSIONS;
    }
}
