package com.voxticket.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Spring AI Voyage model against a stub server: query path strips the
 * bge-m3 instruction and uses input_type=query, document path uses
 * input_type=document, batched-document path embeds a whole list as
 * input_type=document in one call, dimensions are fixed at 1024 without
 * a live call.
 */
class VoyageEmbeddingModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private VoyageEmbeddingModel startModel(AtomicReference<String> requestBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            JsonNode request = mapper.readTree(requestBody.get());
            int count = request.get("input").size();
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    data.append(",");
                }
                data.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[1.0");
                for (int j = 1; j < 1024; j++) {
                    data.append(",0.0");
                }
                data.append("]}");
            }
            String body = "{\"object\":\"list\",\"data\":[" + data + "],\"model\":\"voyage-4-lite\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "voyage-4-lite", Duration.ofSeconds(5));
        return new VoyageEmbeddingModel(client);
    }

    @Test
    void dimensionsAreFixedAt1024() throws Exception {
        VoyageEmbeddingModel model = startModel(new AtomicReference<>());

        assertThat(model.dimensions()).isEqualTo(1024);
    }

    @Test
    void queryPathStripsBgeInstructionAndUsesQueryInputType() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        VoyageEmbeddingModel model = startModel(requestBody);

        // embedForResponse() goes through call() with the library's own
        // request construction, so this test no longer depends on
        // EmbeddingOptions.EMPTY, which does not exist in Spring AI 2.0.1.
        EmbeddingResponse response = model.embedForResponse(
                List.of("Represent this sentence for searching relevant passages: refund timing"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).hasSize(1024);
        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(request.get("input_type").asText()).isEqualTo("query");
        assertThat(request.get("input").get(0).asText()).isEqualTo("refund timing");
    }

    @Test
    void documentPathUsesDocumentInputType() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        VoyageEmbeddingModel model = startModel(requestBody);

        float[] vector = model.embed(Document.builder().text("a policy document").build());

        assertThat(vector).hasSize(1024);
        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(request.get("input_type").asText()).isEqualTo("document");
        assertThat(request.get("input").get(0).asText()).isEqualTo("a policy document");
    }

    @Test
    void batchedDocumentPathUsesDocumentInputTypeInOneCall() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        VoyageEmbeddingModel model = startModel(requestBody);

        // Null options and null batching strategy: the model embeds the
        // whole list as one batch. The stub server records only the last
        // request body, so a single recorded request with all 3 inputs
        // proves the batch was one Voyage call, not 3.
        List<float[]> vectors = model.embed(
                List.of(
                        Document.builder().text("first policy").build(),
                        Document.builder().text("second policy").build(),
                        Document.builder().text("third policy").build()),
                null, null);

        assertThat(vectors).hasSize(3);
        assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(1024));
        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(request.get("input_type").asText()).isEqualTo("document");
        assertThat(request.get("input")).hasSize(3);
        assertThat(request.get("input").get(0).asText()).isEqualTo("first policy");
        assertThat(request.get("input").get(2).asText()).isEqualTo("third policy");
    }
}
