package com.voxticket.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises REAL retrieval quality against a live Ollama (bge-m3) instance.
 * Excluded from the default `mvn test` run (see pom.xml surefire
 * excludedGroups) - normal automated tests must not depend on live Ollama.
 *
 * To run manually:
 *   1. ollama pull bge-m3
 *   2. Make sure Ollama is running (default http://localhost:11434)
 *   3. mvn test -Dgroups=manual -DexcludedGroups=
 */
@Tag("manual")
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
class RagRetrievalQualityManualTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private RagService ragService;

    @Test
    void englishReturnQuestionRetrievesTheReturnsPolicy() {
        var results = ragService.searchPolicy("How many days do I have to return an item?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).category()).isEqualTo("returns-policy");
    }

    @Test
    void urduRefundQuestionRetrievesRefundTiming() {
        var results = ragService.searchPolicy("رقم کی واپسی میں کتنا وقت لگتا ہے؟");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).category()).isEqualTo("refund-timing");
    }

    @Test
    void romanUrduCancellationQuestionRetrievesCancellationPolicy() {
        var results = ragService.searchPolicy("kya main apna order cancel kar sakta hoon?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).category()).isEqualTo("cancellation-policy");
    }

    @Test
    void codeSwitchedQuestionRetrievesShippingPolicy() {
        var results = ragService.searchPolicy("mera order kab deliver hoga, tracking kaise check karoon?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).category()).isEqualTo("shipping-and-delivery");
    }
}