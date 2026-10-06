package com.voxticket.api.voice;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import java.time.Duration;

/**
 * Mints short-lived LiveKit JWTs so a browser can join a voice room.
 * Signing is local HMAC - no network call, no LiveKit server round-trip.
 *
 * <p>Token shape (per the livekit server-sdk-kotlin README): identity +
 * {@code RoomJoin(true)} (join and publish, i.e. microphone) scoped to one
 * room via {@code RoomName}. TTL is 30 minutes: long enough for a call,
 * short enough that a leaked token is useless tomorrow.
 */
public class LiveKitTokenService {

    private final String serverUrl;
    private final String apiKey;
    private final String apiSecret;

    public LiveKitTokenService(String serverUrl, String apiKey, String apiSecret) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    public VoiceTokenResponse mint(String roomName, String identity) {
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(identity);
        token.setName(identity);
        token.addGrants(new RoomJoin(true), new RoomName(roomName));
        token.setTtl(Duration.ofMinutes(30).toMillis());
        return new VoiceTokenResponse(serverUrl, token.toJwt(), roomName, identity);
    }

    public String serverUrl() {
        return serverUrl;
    }
}
