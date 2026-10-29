package com.voxticket.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal Voyage embeddings client: plain JDK {@link HttpClient}, no new
 * dependency, no SDK. Two clients exist in production: the RAG bean
 * from EmbeddingConfiguration and the routing client built by
 * RoutingConfiguration from the same properties. They share the
 * timeout and the retry rule below, but not a connection pool.
 *
 * <p>Voyage API: POST {@code {baseUrl}/embeddings} with
 * {@code {"model", "input": [...], "input_type": "query"|"document",
 * "output_dimension": 1024, "truncation": true}}. The response
 * {@code data} array carries {@code index} + {@code embedding} per
 * input; results are reordered by index, so batch order is preserved
 * even if the API ever returns them out of order.
 *
 * <p>Failure semantics, chosen for voice latency: a timeout per
 * attempt, from properties (default 5 seconds, overridable with
 * VOYAGE_TIMEOUT_SECONDS), one retry only on 429/5xx after 500ms, no retry on
 * 400/401/403 (a bad key or bad request will not fix itself by
 * retrying, and retrying it would only stall the turn). A failed
 * embedding therefore surfaces quickly as
 * {@link VoyageEmbeddingException} instead of hanging the turn until
 * the Python worker's 60-second stream timeout. The API key and the
 * input texts are never logged.
 */
public class VoyageEmbeddingClient {

    public enum InputType {
        QUERY("query"),
        DOCUMENT("document");

        private final String value;

        InputType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final String DEFAULT_BASE_URL = "https://api.voyageai.com/v1";
    public static final int EXPECTED_DIMENSIONS = 1024;

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VoyageEmbeddingClient(String baseUrl, String apiKey, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Voyage API key is missing - set VOYAGE_API_KEY in the environment when "
                            + "a Voyage provider is selected (routing can use VOYAGE_ROUTING_API_KEY "
                            + "instead; the key itself is never logged)");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Voyage model must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Voyage base URL must not be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Voyage timeout must be positive");
        }
        String base = baseUrl.strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/embeddings";
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    /** Single-text convenience: one Voyage call with one input. */
    public float[] embed(String text, InputType inputType) {
        return embed(List.of(text == null ? "" : text), inputType).get(0);
    }

    /** Batch call: one HTTP request for all texts, results in input order. */
    public List<float[]> embed(List<String> texts, InputType inputType) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> inputs = texts.stream().map(t -> t == null ? "" : t).toList();
        long startNanos = System.nanoTime();
        VoyageEmbeddingException lastFailure = null;
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                List<float[]> result = embedOnce(inputs, inputType);
                log.debug("event=voyage_embedding model={} inputType={} count={} durationMs={}",
                        model, inputType.value(), inputs.size(),
                        Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
                return result;
            } catch (VoyageEmbeddingException e) {
                lastFailure = e;
                if (attempt == 0 && e.isRetryable()) {
                    try {
                        Thread.sleep(RETRY_DELAY);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new VoyageEmbeddingException("Voyage embedding retry interrupted", interrupted);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw lastFailure;
    }

    private List<float[]> embedOnce(List<String> inputs, InputType inputType) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", inputs,
                    "input_type", inputType.value(),
                    "output_dimension", EXPECTED_DIMENSIONS,
                    "truncation", true));
        } catch (IOException e) {
            throw new VoyageEmbeddingException("Failed to serialize Voyage embedding request", e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new VoyageEmbeddingException("Voyage embedding request failed (model=" + model + ")", e);
        }
        int status = response.statusCode();
        if (status == 429 || (status >= 500 && status < 600)) {
            throw new VoyageEmbeddingException("Voyage embedding request failed with HTTP " + status, true);
        }
        if (status != 200) {
            throw new VoyageEmbeddingException("Voyage embedding request failed with HTTP " + status);
        }
        return parseEmbeddings(response.body(), inputs.size());
    }

    private List<float[]> parseEmbeddings(String body, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new VoyageEmbeddingException("Voyage embedding response was not valid JSON", e);
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw new VoyageEmbeddingException(
                    "Voyage embedding response had " + (data == null ? "no" : data.size())
                            + " results for " + expectedCount + " inputs");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(item -> item.path("index").asInt()));
        List<float[]> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray() || embedding.size() != EXPECTED_DIMENSIONS) {
                throw new VoyageEmbeddingException(
                        "Voyage embedding had dimension " + (embedding == null ? "null" : embedding.size())
                                + ", expected " + EXPECTED_DIMENSIONS
                                + " - check that VOYAGE_MODEL is a 1024-dimensional Voyage model");
            }
            float[] vector = new float[EXPECTED_DIMENSIONS];
            for (int i = 0; i < EXPECTED_DIMENSIONS; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            result.add(vector);
        }
        return result;
    }
}
