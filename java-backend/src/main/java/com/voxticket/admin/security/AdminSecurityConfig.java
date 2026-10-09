package com.voxticket.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 12 (P4): admin API access control.
 *
 * <ul>
 *   <li>{@code /api/v1/admin/**} requires the {@code ADMIN} role via HTTP Basic.</li>
 *   <li>{@code /api/v1/voice/telemetry} and {@code /api/v1/voice/worker-heartbeat}
 *       are gated by the shared-secret {@link VoiceTelemetrySecretFilter}, not the admin role,
 *       so the Python worker never needs admin credentials.</li>
 *   <li>Business endpoints (chat, voice turns, health, static frontend) stay public.</li>
 *   <li>Default-deny: anything not matched above requires authentication.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);

    @Value("${voxticket.admin.username:admin}")
    private String adminUsername;

    @Value("${voxticket.admin.password:admin}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http, VoiceTelemetrySecretFilter telemetrySecretFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(telemetrySecretFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/chat",
                                "/api/v1/voice/turn",
                                "/api/v1/voice/turn/stream",
                                "/api/v1/voice/token",
                                // Public demo support (dev/test profile only): serves a
                                // seeded customer + orders to the demo page.
                                "/api/v1/demo/**",
                                // Telemetry endpoints: authenticated by the
                                // VoiceTelemetrySecretFilter (shared secret), not the ADMIN role.
                                "/api/v1/voice/telemetry",
                                "/api/v1/voice/worker-heartbeat",
                                "/actuator/health",
                                // The container forwards sendError() responses here; without
                                // this, a filter-issued 403 is re-challenged as 401.
                                "/error",
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/*.js",
                                "/*.css",
                                "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .httpBasic(basic -> {
                });
        if ("admin".equals(adminPassword)) {
            log.warn("SECURITY: voxticket.admin.password is still the default 'admin' "
                    + "(demo credentials - set ADMIN_PASSWORD in production)");
        }
        return http.build();
    }

    /**
     * The admin password comes from configuration (env var in production).
     * Plain-text comparison via {@code {noop}} is deliberate: hashing is the
     * deployer's job via the env var / secret store, not the app's.
     */
    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    public InMemoryUserDetailsManager adminUsers() {
        // No {noop} prefix: the NoOpPasswordEncoder bean above compares the
        // raw configured password directly.
        UserDetails admin = User.withUsername(adminUsername)
                .password(adminPassword)
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
