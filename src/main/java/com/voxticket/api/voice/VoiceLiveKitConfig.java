package com.voxticket.api.voice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the LiveKit token service for the dev/test voice path. Voice calling
 * is a dev/test-only surface in this FYP (same posture as the chat and admin
 * controllers), so this configuration is profile-gated as well.
 */
@Configuration
@Profile({"dev", "test"})
@EnableConfigurationProperties(LiveKitVoiceProperties.class)
public class VoiceLiveKitConfig {

    @Bean
    public LiveKitTokenService liveKitTokenService(LiveKitVoiceProperties props) {
        require("voxticket.voice.livekit.url (env LIVEKIT_URL)", props.url());
        require("voxticket.voice.livekit.api-key (env LIVEKIT_API_KEY)", props.apiKey());
        require("voxticket.voice.livekit.api-secret (env LIVEKIT_API_SECRET)", props.apiSecret());
        return new LiveKitTokenService(props.url(), props.apiKey(), props.apiSecret());
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Voice calling is not configured: " + name + " is missing. "
                            + "Set it before starting the app with the dev/test profile.");
        }
    }
}
