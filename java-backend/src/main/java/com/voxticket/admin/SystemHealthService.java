package com.voxticket.admin;

import com.voxticket.admin.dto.AiAnalyticsDtos.ComponentHealthDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.SystemHealthDto;
import com.voxticket.agent.AiProvider;
import com.voxticket.agent.AiProvidersProperties;
import com.voxticket.agent.ProviderProperties;
import com.voxticket.persistence.entity.VoiceWorkerHeartbeatEntity;
import com.voxticket.persistence.repository.VoiceWorkerHeartbeatRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): aggregated system health for the admin System Health page.
 * Every probe is best-effort and local - a failing dependency reports
 * DOWN/DEGRADED/UNKNOWN but never fails the health endpoint itself. Provider
 * API keys are never exposed: only presence/absence is reported.
 */
@Service
@Transactional(readOnly = true)
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration WORKER_STALE_AFTER = Duration.ofSeconds(90);

    private final JdbcTemplate jdbc;
    private final AiProvidersProperties providers;
    private final VoiceWorkerHeartbeatRepository heartbeatRepository;
    private final String ollamaBaseUrl;
    private final HttpClient httpClient;

    public SystemHealthService(
            JdbcTemplate jdbc,
            AiProvidersProperties providers,
            VoiceWorkerHeartbeatRepository heartbeatRepository,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.heartbeatRepository = heartbeatRepository;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT)
                .build();
    }

    public SystemHealthDto getHealth() {
        Instant now = Instant.now();
        List<ComponentHealthDto> components = new ArrayList<>();
        components.add(new ComponentHealthDto("backend", "UP", "application running", now));
        components.add(probePostgres(now));
        components.add(probePgvector(now));
        components.add(probeOllama(now));
        components.addAll(probeProviders(now));
        components.addAll(probeVoiceWorkers(now));
        return new SystemHealthDto(overallStatus(components), now, List.copyOf(components));
    }

    private static String overallStatus(List<ComponentHealthDto> components) {
        boolean degraded = false;
        for (ComponentHealthDto c : components) {
            if ("DOWN".equals(c.status())) {
                return "DOWN";
            }
            if ("DEGRADED".equals(c.status()) || "UNKNOWN".equals(c.status())) {
                degraded = true;
            }
        }
        return degraded ? "DEGRADED" : "UP";
    }

    private ComponentHealthDto probePostgres(Instant now) {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) {
                return new ComponentHealthDto("postgresql", "UP", "SELECT 1 ok", now);
            }
            return new ComponentHealthDto("postgresql", "DEGRADED", "unexpected SELECT 1 result", now);
        } catch (Exception e) {
            log.warn("event=health_probe_failed component=postgresql errorType={}", e.getClass().getSimpleName());
            return new ComponentHealthDto("postgresql", "DOWN", "unreachable: " + e.getClass().getSimpleName(), now);
        }
    }

    private ComponentHealthDto probePgvector(Instant now) {
        try {
            Long count = jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class);
            return new ComponentHealthDto(
                    "pgvector", "UP", count + " knowledge embeddings indexed", now);
        } catch (Exception e) {
            log.warn("event=health_probe_failed component=pgvector errorType={}", e.getClass().getSimpleName());
            return new ComponentHealthDto("pgvector", "DOWN", "unreachable: " + e.getClass().getSimpleName(), now);
        }
    }

    private ComponentHealthDto probeOllama(Instant now) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                return new ComponentHealthDto("ollama", "UP", "embeddings endpoint reachable", now);
            }
            return new ComponentHealthDto(
                    "ollama", "DEGRADED", "HTTP " + response.statusCode() + " from /api/tags", now);
        } catch (Exception e) {
            log.warn("event=health_probe_failed component=ollama errorType={}", e.getClass().getSimpleName());
            return new ComponentHealthDto("ollama", "DOWN", "unreachable: " + e.getClass().getSimpleName(), now);
        }
    }

    private List<ComponentHealthDto> probeProviders(Instant now) {
        List<ComponentHealthDto> out = new ArrayList<>();
        for (AiProvider provider : AiProvider.values()) {
            String name = "provider:" + provider.name().toLowerCase();
            try {
                ProviderProperties props = providers.forProvider(provider);
                if (!props.enabled()) {
                    out.add(new ComponentHealthDto(name, "DEGRADED", "disabled in configuration", now));
                } else if (props.apiKey() == null || props.apiKey().isBlank()) {
                    out.add(new ComponentHealthDto(
                            name, "DEGRADED", "enabled but no API key configured", now));
                } else {
                    out.add(new ComponentHealthDto(name, "UP", "enabled, API key present", now));
                }
            } catch (Exception e) {
                out.add(new ComponentHealthDto(name, "UNKNOWN", "not configured", now));
            }
        }
        return out;
    }

    private List<ComponentHealthDto> probeVoiceWorkers(Instant now) {
        List<VoiceWorkerHeartbeatEntity> heartbeats;
        try {
            heartbeats = heartbeatRepository.findAll();
        } catch (Exception e) {
            log.warn("event=health_probe_failed component=voiceWorker errorType={}", e.getClass().getSimpleName());
            return List.of(new ComponentHealthDto("voiceWorker", "UNKNOWN", "heartbeat table unreadable", now));
        }
        if (heartbeats.isEmpty()) {
            return List.of(new ComponentHealthDto("voiceWorker", "UNKNOWN", "no heartbeats received yet", now));
        }
        List<ComponentHealthDto> out = new ArrayList<>();
        for (VoiceWorkerHeartbeatEntity hb : heartbeats) {
            boolean stale = hb.getLastSeenAt() == null
                    || hb.getLastSeenAt().isBefore(now.minus(WORKER_STALE_AFTER));
            out.add(new ComponentHealthDto(
                    "voiceWorker:" + hb.getWorkerId(),
                    stale ? "DEGRADED" : "UP",
                    stale ? "last heartbeat over 90s ago"
                            : "active rooms: " + hb.getActiveRooms(),
                    now));
        }
        return out;
    }
}
