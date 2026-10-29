package com.voxticket.embedding;

/** Unchecked: a Voyage embedding request failed (network, auth, rate limit, bad response). Never carries the API key or the input texts. */
public class VoyageEmbeddingException extends RuntimeException {

    private final boolean retryable;

    public VoyageEmbeddingException(String message) {
        this(message, false);
    }

    public VoyageEmbeddingException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public VoyageEmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = false;
    }

    /** True only for 429/5xx, where one retry might succeed; 400/401/403 and bad responses are not retried. */
    public boolean isRetryable() {
        return retryable;
    }
}
