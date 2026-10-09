package com.voxticket.api.voice;

import com.voxticket.admin.security.VoiceTelemetrySecretFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "voxticket.voice.telemetry-secret=<redacted>")
class TelemetrySecretDiagnosticTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private VoiceTelemetrySecretFilter filter;

    @Test
    void diagnoseDirectFilterInvocation() throws Exception {
        Field f = VoiceTelemetrySecretFilter.class.getDeclaredField("configuredSecret");
        f.setAccessible(true);
        String beanSecret = (String) f.get(filter);
        System.out.println("DIAG bean configuredSecret = [" + beanSecret + "] length=" + beanSecret.length());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/voice/telemetry");
        request.addHeader("X-VoxTicket-Telemetry-Secret", "test-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String headerBack = request.getHeader("X-VoxTicket-Telemetry-Secret");
        System.out.println("DIAG header as seen by filter = [" + headerBack + "] length="
                + (headerBack == null ? -1 : headerBack.length()));

        final boolean[] chainContinued = {false};
        FilterChain chain = (req, res) -> chainContinued[0] = true;

        Method shouldNotFilter = VoiceTelemetrySecretFilter.class
                .getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
        shouldNotFilter.setAccessible(true);
        boolean skipped = (boolean) shouldNotFilter.invoke(filter, request);
        System.out.println("DIAG shouldNotFilter = " + skipped);

        filter.doFilter(request, response, chain);
        System.out.println("DIAG response status = " + response.getStatus());
        System.out.println("DIAG chain continued = " + chainContinued[0]);

        if (headerBack != null) {
            StringBuilder sb = new StringBuilder("DIAG char diff: ");
            int max = Math.max(headerBack.length(), beanSecret.length());
            for (int i = 0; i < max; i++) {
                int hc = i < headerBack.length() ? headerBack.charAt(i) : -1;
                int bc = i < beanSecret.length() ? beanSecret.charAt(i) : -1;
                if (hc != bc) {
                    sb.append("[").append(i).append(":").append(hc).append("!=").append(bc).append("]");
                }
            }
            System.out.println(sb);
        }
    }
}
