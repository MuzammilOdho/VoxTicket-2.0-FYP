package com.voxticket.api.voice;

/** What the browser needs to join a LiveKit voice room: server URL + JWT. */
public record VoiceTokenResponse(String url, String token, String room, String identity) {
}
