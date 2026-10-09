package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.voxticket.audit.AuditEventBus;
import com.voxticket.conversation.Channel;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.ConversationSessionRecord;
import com.voxticket.persistence.entity.VoiceCallSessionEntity;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import com.voxticket.persistence.repository.VoiceCallSessionRepository;
import com.voxticket.persistence.repository.VoiceCallTurnMetricRepository;
import com.voxticket.persistence.repository.VoiceWorkerHeartbeatRepository;
import java.util.UUID;
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
 * Phase 12 (P4): the worker telemetry contract. The secret filter gates the
 * endpoints; accepted batches flow through the async bus and land in the V9
 * voice tables after {@link AuditEventBus#flush()}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "voxticket.voice.telemetry-secret=test-secret")
class VoiceTelemetrySecretTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Autowired
    private AuditEventBus bus;
    @Autowired
    private ConversationSessionRecordRepository sessionRepository;
    @Autowired
    private VoiceCallSessionRepository callRepository;
    @Autowired
    private VoiceCallTurnMetricRepository turnMetricRepository;
    @Autowired
    private VoiceWorkerHeartbeatRepository heartbeatRepository;

    @Test
    void missingSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSecret_isForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .header("X-VoxTicket-Telemetry-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"w1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void telemetryBatch_isAcceptedAndPersistedAfterFlush() throws Exception {
        String room = "voice-tel-" + UUID.randomUUID();
        sessionRepository.save(new ConversationSessionRecord(room, Channel.PHONE, IdentityAssurance.ANONYMOUS));

        String body = """
                {"workerId":"worker-1",
                 "calls":[{"room":"%s","traceId":"trace-1","outcome":"COMPLETED","bargeInCount":2,
                           "startedAt":"2026-10-06T10:00:00Z","endedAt":"2026-10-06T10:01:30Z"}],
                 "turns":[{"room":"%s","turnNumber":1,"traceId":"trace-1",
                           "sttLatencyMs":120.5,"brainTtftMs":800.0,"ttsFirstAudioMs":300.25,"e2eMs":1500.0,
                           "aborted":false,"bargeIn":true,"sttLanguage":"en",
                           "sttProvider":"assemblyai","sttModel":"universal-3.6",
                           "ttsProvider":"cartesia","ttsModel":"sonic-3.6","error":null},
                          {"room":"%s","turnNumber":2,"traceId":"trace-2",
                           "sttLatencyMs":null,"brainTtftMs":null,"ttsFirstAudioMs":null,"e2eMs":null,
                           "aborted":true,"bargeIn":false,"sttLanguage":"ur",
                           "sttProvider":null,"sttModel":null,"ttsProvider":null,"ttsModel":null,
                           "error":"brain unreachable"}]}"""
                .formatted(room, room, room);

        mockMvc.perform(post("/api/v1/voice/telemetry")
                        .header("X-VoxTicket-Telemetry-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        bus.flush();

        VoiceCallSessionEntity call = callRepository.findByRoom(room).orElseThrow();
        assertThat(call.getOutcome()).isEqualTo("COMPLETED");
        assertThat(call.getBargeInCount()).isEqualTo(2);
        assertThat(call.getWorkerId()).isEqualTo("worker-1");

        var metrics = turnMetricRepository.findByCallIdOrderByTurnNumberAsc(call.getId());
        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(0).getSttLatencyMs()).isEqualTo(120.5);
        assertThat(metrics.get(0).getBrainTtftMs()).isEqualTo(800.0);
        assertThat(metrics.get(0).getTtsFirstAudioMs()).isEqualTo(300.25);
        assertThat(metrics.get(0).getBargeIn()).isTrue();
        assertThat(metrics.get(1).getAborted()).isTrue();
        assertThat(metrics.get(1).getSttLatencyMs()).isNull();
        assertThat(metrics.get(1).getError()).isEqualTo("brain unreachable");
    }

    @Test
    void heartbeat_isAcceptedAndPersistedAfterFlush() throws Exception {
        String workerId = "worker-hb-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/voice/worker-heartbeat")
                        .header("X-VoxTicket-Telemetry-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"%s","sttProvider":"assemblyai","ttsProvider":"cartesia","activeRooms":3}"""
                                .formatted(workerId)))
                .andExpect(status().isAccepted());

        bus.flush();

        var hb = heartbeatRepository.findById(workerId).orElseThrow();
        assertThat(hb.getSttProvider()).isEqualTo("assemblyai");
        assertThat(hb.getTtsProvider()).isEqualTo("cartesia");
        assertThat(hb.getActiveRooms()).isEqualTo(3);
        assertThat(hb.getLastSeenAt()).isNotNull();
    }
}
