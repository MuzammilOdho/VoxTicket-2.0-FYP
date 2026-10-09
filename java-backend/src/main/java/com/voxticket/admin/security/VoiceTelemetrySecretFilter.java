package com.voxticket.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret gate for the Python LiveKit worker's telemetry endpoints
 * ({@code POST /api/v1/voice/telemetry} and
 * {@code POST /api/v1/voice/worker-heartbeat}).
 *
 * <p>The worker sends the configured secret as the
 * {@code X-VoxTicket-Telemetry-Secret} header. A mismatch (including a
 * missing header when a secret is configured) is rejected with 403 and the
 * request never reaches the controller. When no secret is configured the
 * endpoints accept without one, but a WARN is logged on every such request
 * so an unprotected telemetry surface is never silent.
 *
 * <p>Failure-isolation: this filter only reads a header and compares
 * strings - it performs no I/O and cannot delay the audio pipeline.
 */
@Component
public class VoiceTelemetrySecretFilter extends OncePerRequestFilter {

    static final String SECRET_HEADER = "X-VoxTicket-Telemetry-Secret";
    static final String TELEMETRY_PATH = "/api/v1/voice/telemetry";
    static final String HEARTBEAT_PATH = "/api/v1/voice/worker-heartbeat";

    private static final Logger log = LoggerFactory.getLogger(VoiceTelemetrySecretFilter.class);

    private final String configuredSecret;

    public VoiceTelemetrySecretFilter(
            @Value("${voxticket.voice.telemetry-secret:}") String configuredSecret) {
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(TELEMETRY_PATH.equals(path) || HEARTBEAT_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (configuredSecret.isEmpty()) {
            log.warn("event=voice_telemetry_unprotected path={} - no voxticket.voice.telemetry-secret "
                    + "configured; telemetry endpoint accepts requests without a secret", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(SECRET_HEADER);
        if (provided == null || !constantTimeEquals(provided, configuredSecret)) {
            log.warn("event=voice_telemetry_forbidden path={}", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid telemetry secret");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
