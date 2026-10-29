package com.voxticket.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voxticket.routing.embedding.VoyageRoutingEmbeddingService;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Routing adapter against a stub Voyage server: 1024-d, normalized, batched. */
class VoyageRoutingEmbeddingServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private VoyageRoutingEmbeddingService startService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            // One 1024-d vector per input, each with norm 2 (unnormalized
            // on purpose, so the adapter's L2 normalization is exercised):
            // input k gets 2.0 at position k, 0.0 elsewhere. The stub used
            // to always return two vectors, which made the client (rightly)
            // reject a single-input call for returning 2 results for 1 input.
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            int count = request.path("input").size();
            if (count == 0) {
                count = 1;
            }
            StringBuilder data = new StringBuilder();
            for (int k = 0; k < count; k++) {
                if (k > 0) {
                    data.append(',');
                }
                data.append("{\"object\":\"embedding\",\"index\":").append(k).append(",\"embedding\":[");
                int hot = Math.min(k, 1023);
                for (int i = 0; i < 1024; i++) {
                    if (i > 0) {
                        data.append(',');
                    }
                    data.append(i == hot ? "2.0" : "0.0");
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
        return new VoyageRoutingEmbeddingService(client);
    }

    private static double norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    @Test
    void dimensionIs1024AndVectorsAreNormalized() throws Exception {
        VoyageRoutingEmbeddingService service = startService();

        assertThat(service.dimension()).isEqualTo(1024);
        float[] vector = service.embed("hello");
        assertThat(vector).hasSize(1024);
        assertThat(norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(vector[0]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void batchReturnsOneNormalizedVectorPerInput() throws Exception {
        VoyageRoutingEmbeddingService service = startService();

        List<float[]> vectors = service.embedBatch(List.of("first", "second"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(vectors.get(1)[1]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }
}
