package com.voxticket.api.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LiveKit credentials for browser voice calling. Bound from
 * {@code voxticket.voice.livekit.*} (see APPLICATION_YAML_SNIPPET.yaml);
 * values come from env vars so no secret is ever committed.
 *
 * <p>Missing values fail fast in {@link VoiceLiveKitConfig} - a voice
 * deployment without a LiveKit server to talk to must not start quietly.
 */
@ConfigurationProperties(prefix = "voxticket.voice.livekit")
public record LiveKitVoiceProperties(String url, String apiKey, String apiSecret) {
}
