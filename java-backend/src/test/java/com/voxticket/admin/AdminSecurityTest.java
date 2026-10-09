package com.voxticket.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 12 (P4): the admin API requires the ADMIN role; business and
 * telemetry endpoints keep their own access rules. Runs against a throwaway
 * pgvector/pg16 Testcontainers database; skipped where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "voxticket.voice.telemetry-secret=test-secret")
class AdminSecurityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // @AutoConfigureMockMvc is not in the Boot 4.1 test jars on the
        // classpath; build the MockMvc manually with the security filter
        // chain applied so the assertions exercise the real configuration.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void adminSummaryV2_withoutCredentials_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/summary/v2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSummaryV2_withWrongCredentials_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/summary/v2").with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSummaryV2_withAdminCredentials_isOk() throws Exception {
        mockMvc.perform(get("/api/v1/admin/summary/v2").with(httpBasic("admin", "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void legacyAdminSummary_withAdminCredentials_isOk() throws Exception {
        // The pre-existing AdminController keeps working under the same role.
        mockMvc.perform(get("/api/v1/admin/summary").with(httpBasic("admin", "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_isPermittedWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void telemetry_withoutSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void telemetry_withWrongSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .header("X-VoxTicket-Telemetry-Secret", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void telemetry_withCorrectSecret_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .header("X-VoxTicket-Telemetry-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\",\"turns\":[],\"calls\":[]}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void voiceTurn_isPermittedWithoutAuth() throws Exception {
        // The brain endpoint is called by the worker on the audio pipeline;
        // it is not part of the admin surface and must not 401. A 400 for
        // the invalid body proves the request reached the controller.
        mockMvc.perform(post("/api/v1/voice/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
