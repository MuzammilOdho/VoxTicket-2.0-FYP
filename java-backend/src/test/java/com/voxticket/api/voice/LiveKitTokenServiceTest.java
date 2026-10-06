package com.voxticket.api.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no Spring context, no network): JWT minting is local HMAC
 * signing, so we can assert the token structure and grants offline.
 */
class LiveKitTokenServiceTest {

    @Test
    void mintedTokenIsAJwtCarryingTheRoomAndIdentity() {
        LiveKitTokenService service = new LiveKitTokenService("ws://localhost:7880", "devkey", "devsecret");

        VoiceTokenResponse response = service.mint("voice-room-9", "caller-abc");

        assertThat(response.url()).isEqualTo("ws://localhost:7880");
        assertThat(response.room()).isEqualTo("voice-room-9");
        assertThat(response.identity()).isEqualTo("caller-abc");

        String[] parts = response.token().split("\\.");
        assertThat(parts).hasSize(3); // header.payload.signature
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("voice-room-9");
        assertThat(payload).contains("caller-abc");
    }
}
