package com.voxticket.routing.eval;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 3: opt-in provider-backed E2E precondition probe.
 *
 * <p>Enable with {@code -Dvoxticket.phase3.provider-e2e=true}. Never part of
 * normal test execution. This probe verifies preconditions only - it makes no
 * provider network calls, so it cannot burn quota or mask a quota outage as a
 * router error.
 *
 * <p>The provider-backed subset (stratified end-to-end turns capturing latency,
 * task success, tool correctness, provider/model selected and token usage) is
 * future research work: it is NOT validly executable while provider quota is
 * exhausted, and provider availability never invalidates the deterministic
 * router-only Phase 3 experiment.
 */
@EnabledIfSystemProperty(named = "voxticket.phase3.provider-e2e", matches = "true")
class Phase3ProviderE2E {

    @Test
    void probePreconditions() throws Exception {
        boolean geminiKey = hasEnv("GEMINI_API_KEY");
        boolean groqKey = hasEnv("GROQ_API_KEY");
        boolean cerebrasKey = hasEnv("CEREBRAS_API_KEY");

        // Known ground truth (user's live E2E, 2026-09-26): Google Tier 1
        // (GOOGLE/gemini-3.6-flash) returned HTTP 429 "free-tier quota exceeded".
        // Quota recovery has not been verified; no call is attempted here.
        String reason;
        if (!geminiKey && !groqKey && !cerebrasKey) {
            reason = "no provider credentials in environment "
                    + "(GEMINI_API_KEY/GROQ_API_KEY/CEREBRAS_API_KEY all absent)";
        } else {
            reason = "Google Tier 1 (GOOGLE/gemini-3.6-flash) returned HTTP 429 "
                    + "free-tier quota exceeded on 2026-09-26 (user live E2E test); "
                    + "quota recovery unverified since, so no provider call was attempted "
                    + "(a quota error must not be misread as a router error). "
                    + "Credential presence: GEMINI_API_KEY=" + geminiKey
                    + " GROQ_API_KEY=" + groqKey + " CEREBRAS_API_KEY=" + cerebrasKey;
        }

        ObjectNode report = new JsonMapper().createObjectNode();
        report.put("status", "NOT_VALIDLY_EXECUTABLE");
        report.put("reason", reason);
        report.put("checkedAt", Instant.now().toString());
        report.put("note", "Provider-backed E2E remains future research work; the "
                + "deterministic router-only Phase 3 experiment stands on its own.");
        Files.createDirectories(Paths.get("evaluation/routing/results"));
        new JsonMapper().writerWithDefaultPrettyPrinter().writeValue(
                Paths.get("evaluation/routing/results/provider-e2e.json").toFile(), report);
        System.out.println("PROVIDER-BACKED E2E: NOT_VALIDLY_EXECUTABLE reason=" + reason);
    }

    private static boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
