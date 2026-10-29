package com.voxticket.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Voyage client against a local stub server - no live API key, no network
 * beyond loopback. The stub returns 1024-dimensional vectors so dimension
 * validation is exercised with realistic sizes.
 */
class VoyageEmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static float[] unitVector(int index) {
        float[] vector = new float[1024];
        vector[index] = 1.0f;
        return vector;
    }

    private static String voyageResponse(List<float[]> vectors) throws IOException {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < vectors.size(); i++) {
            if (i > 0) {
                data.append(",");
            }
            data.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            float[] vector = vectors.get(i);
            for (int j = 0; j < vector.length; j++) {
                if (j > 0) {
                    data.append(",");
                }
                data.append(vector[j]);
            }
            data.append("]}");
        }
        return "{\"object\":\"list\",\"data\":[" + data + "],\"model\":\"voyage-4-lite\",\"usage\":{\"total_tokens\":10}}";
    }

    private VoyageEmbeddingClient startClient(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", handler);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new VoyageEmbeddingClient(baseUrl, "test-key", "voyage-4-lite", Duration.ofSeconds(5));
    }

    private interface StubHandler extends com.sun.net.httpserver.HttpHandler {
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void batchReturnsVectorsInInputOrderWithQueryInputType() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        VoyageEmbeddingClient client = startClient(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, voyageResponse(List.of(unitVector(0), unitVector(1))));
        });

        List<float[]> result = client.embed(List.of("first", "second"), VoyageEmbeddingClient.InputType.QUERY);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)[0]).isEqualTo(1.0f);
        assertThat(result.get(1)[1]).isEqualTo(1.0f);
        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(request.get("model").asText()).isEqualTo("voyage-4-lite");
        assertThat(request.get("input_type").asText()).isEqualTo("query");
        assertThat(request.get("output_dimension").asInt()).isEqualTo(1024);
        assertThat(request.get("input").get(0).asText()).isEqualTo("first");
        assertThat(request.get("input").get(1).asText()).isEqualTo("second");
    }

    @Test
    void documentInputTypeIsSentForDocuments() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        VoyageEmbeddingClient client = startClient(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, voyageResponse(List.of(unitVector(5))));
        });

        client.embed("a policy document", VoyageEmbeddingClient.InputType.DOCUMENT);

        assertThat(mapper.readTree(requestBody.get()).get("input_type").asText()).isEqualTo("document");
    }

    @Test
    void wrongDimensionIsRejected() throws Exception {
        VoyageEmbeddingClient client = startClient(exchange -> {
            float[] shortVector = new float[512];
            shortVector[0] = 1.0f;
            send(exchange, 200, voyageResponse(List.of(shortVector)));
        });

        assertThatThrownBy(() -> client.embed("hello", VoyageEmbeddingClient.InputType.QUERY))
                .isInstanceOf(VoyageEmbeddingException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void rateLimitRetriesOnceThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VoyageEmbeddingClient client = startClient(exchange -> {
            if (calls.incrementAndGet() == 1) {
                send(exchange, 429, "{\"detail\":\"rate limited\"}");
            } else {
                send(exchange, 200, voyageResponse(List.of(unitVector(3))));
            }
        });

        List<float[]> result = client.embed(List.of("hello"), VoyageEmbeddingClient.InputType.QUERY);

        assertThat(result.get(0)[3]).isEqualTo(1.0f);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void unauthorizedIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VoyageEmbeddingClient client = startClient(exchange -> {
            calls.incrementAndGet();
            send(exchange, 401, "{\"detail\":\"bad key\"}");
        });

        assertThatThrownBy(() -> client.embed("hello", VoyageEmbeddingClient.InputType.QUERY))
                .isInstanceOf(VoyageEmbeddingException.class)
                .hasMessageContaining("401");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void blankApiKeyFailsFastWithoutAnyRequest() {
        assertThatThrownBy(() -> new VoyageEmbeddingClient(
                "https://api.voyageai.com/v1", "  ", "voyage-4-lite", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VOYAGE_API_KEY");
    }
}
