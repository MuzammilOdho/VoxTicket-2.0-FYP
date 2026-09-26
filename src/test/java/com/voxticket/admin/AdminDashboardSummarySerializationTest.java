package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voxticket.admin.dto.AdminDashboardSummary;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 regression test: the dashboard summary must surface provider/model
 * routing information without ever leaking API keys, secrets, or credentials.
 * Runs without Docker - pure serialization of the DTO.
 */
class AdminDashboardSummarySerializationTest {

    private static AdminDashboardSummary sampleSummary() {
        return new AdminDashboardSummary(
                1, 1, 0, 0, 0, 0,
                Map.of("TIER_1", 2L),
                Map.of("GROQ", 2L),
                Map.of("openai/gpt-oss-20b", 2L),
                Map.of("default", 2L),
                Map.of("prompt", 10L),
                Map.of("GROQ", 10L),
                Map.of("TIER_1", new AdminDashboardSummary.LlmCallMetric(2, 120.5, 0)),
                50.0, 90.0, 2L,
                Map.of(),
                new AdminDashboardSummary.RagMetric(0, 0.0, 0.0));
    }

    @Test
    void serializedSummaryExposesNoSecretsOrCredentials() throws Exception {
        String json = new ObjectMapper().writeValueAsString(sampleSummary()).toLowerCase();

        assertThat(json)
                .doesNotContain("api-key", "apikey", "api_key", "secret", "credential", "password", "bearer");
    }

    @Test
    void serializedSummaryExposesTheIntendedPhase1RoutingFields() throws Exception {
        String json = new ObjectMapper().writeValueAsString(sampleSummary());

        assertThat(json)
                .contains("modelProviderUsage", "modelUsage", "modelSelectionReasons", "llmCallMetrics", "tokenUsageByProvider")
                .contains("GROQ", "TIER_1", "openai/gpt-oss-20b", "default");
    }
}
