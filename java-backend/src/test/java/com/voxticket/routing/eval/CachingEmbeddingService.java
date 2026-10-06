package com.voxticket.routing.eval;

import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingInferenceException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 3: test-scope decorator that caches embeddings by exact input text and
 * counts the real (uncached) delegate calls. Lets the harness prove the
 * "at most one query embedding per example" budget without rerunning ONNX
 * inference for repeated scorings of the same text.
 */
public final class CachingEmbeddingService implements RoutingEmbeddingService, AutoCloseable {

    private final RoutingEmbeddingService delegate;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();
    private final AtomicLong realCalls = new AtomicLong();

    public CachingEmbeddingService(RoutingEmbeddingService delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        String key = text == null ? "" : text;
        float[] cached = cache.get(key);
        if (cached != null) {
            return cached.clone();
        }
        float[] vector = delegate.embed(text);
        realCalls.incrementAndGet();
        cache.put(key, vector.clone());
        return vector.clone();
    }

    /** Number of real delegate inference calls (cache misses). */
    public long realCalls() {
        return realCalls.get();
    }

    /** Closes the delegate when it is {@link AutoCloseable} (the real ONNX service is). */
    public void closeDelegate() {
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RoutingInferenceException("Failed to close routing embedding service", e);
            }
        }
    }

    @Override
    public void close() {
        closeDelegate();
    }
}
