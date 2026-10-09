package com.voxticket.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.voxticket.admin.dto.AiAnalyticsDtos.SystemHealthDto;
import com.voxticket.agent.AiProvider;
import com.voxticket.agent.AiProvidersProperties;
import com.voxticket.agent.ProviderProperties;
import com.voxticket.persistence.entity.VoiceWorkerHeartbeatEntity;
import com.voxticket.persistence.repository.VoiceWorkerHeartbeatRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 12 (P4): health probes are best-effort - a failing dependency
 * reports DOWN but never fails the health endpoint itself.
 */
@ExtendWith(MockitoExtension.class)
class SystemHealthServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private AiProvidersProperties providers;
    @Mock
    private VoiceWorkerHeartbeatRepository heartbeatRepository;

    private SystemHealthService service;

    @BeforeEach
    void setUp() {
        service = new SystemHealthService(jdbc, providers, heartbeatRepository, "http://localhost:11434");
    }

    @Test
    void postgresDown_isReportedDownWhileBackendStaysUp() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        when(jdbc.queryForObject(eq("SELECT count(*) FROM vector_store"), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        when(providers.forProvider(any(AiProvider.class)))
                .thenReturn(new ProviderProperties(true, "dummy-key", null));
        when(heartbeatRepository.findAll()).thenReturn(List.of());

        SystemHealthDto health = service.getHealth();

        assertThat(component(health, "backend").status()).isEqualTo("UP");
        assertThat(component(health, "postgresql").status()).isEqualTo("DOWN");
        assertThat(component(health, "pgvector").status()).isEqualTo("DOWN");
        // Providers report key presence only - never the key value.
        assertThat(component(health, "provider:google").status()).isEqualTo("UP");
        assertThat(component(health, "provider:google").detail()).doesNotContain("dummy-key");
        assertThat(component(health, "voiceWorker").status()).isEqualTo("UNKNOWN");
    }

    @Test
    void providerWithoutKey_isDegraded() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM vector_store"), eq(Long.class))).thenReturn(7L);
        when(providers.forProvider(AiProvider.GOOGLE))
                .thenReturn(new ProviderProperties(true, "", null));
        when(providers.forProvider(AiProvider.GROQ))
                .thenReturn(new ProviderProperties(false, "k", null));
        when(providers.forProvider(AiProvider.CEREBRAS))
                .thenThrow(new IllegalStateException("not configured"));
        when(heartbeatRepository.findAll()).thenReturn(List.of());

        SystemHealthDto health = service.getHealth();

        assertThat(component(health, "provider:google").status()).isEqualTo("DEGRADED");
        assertThat(component(health, "provider:groq").status()).isEqualTo("DEGRADED");
        assertThat(component(health, "provider:cerebras").status()).isEqualTo("UNKNOWN");
        assertThat(component(health, "pgvector").status()).isEqualTo("UP");
        assertThat(component(health, "pgvector").detail()).contains("7");
    }

    @Test
    void staleWorkerHeartbeat_isDegraded() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM vector_store"), eq(Long.class))).thenReturn(0L);
        when(providers.forProvider(any(AiProvider.class)))
                .thenReturn(new ProviderProperties(true, "k", null));
        VoiceWorkerHeartbeatEntity hb = new VoiceWorkerHeartbeatEntity("worker-1");
        hb.setLastSeenAt(Instant.now().minusSeconds(600));
        hb.setActiveRooms(2);
        when(heartbeatRepository.findAll()).thenReturn(List.of(hb));

        SystemHealthDto health = service.getHealth();

        assertThat(component(health, "voiceWorker:worker-1").status()).isEqualTo("DEGRADED");
    }

    private static com.voxticket.admin.dto.AiAnalyticsDtos.ComponentHealthDto component(
            SystemHealthDto health, String name) {
        return health.components().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing component: " + name));
    }
}
