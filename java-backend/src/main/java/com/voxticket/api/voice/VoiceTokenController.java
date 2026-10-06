package com.voxticket.api.voice;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hands a browser everything it needs to join a voice call: the LiveKit
 * server URL and a short-lived JWT scoped to one room.
 *
 * <p>Dev/test only, like the chat and admin surfaces: the token grants
 * microphone publish rights, so it must never be minted for arbitrary
 * rooms/identities in an unauthenticated public deployment.
 */
@RestController
@RequestMapping("/api/v1/voice")
@Profile({"dev", "test"})
public class VoiceTokenController {

    private final LiveKitTokenService tokenService;

    public VoiceTokenController(LiveKitTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/token")
    public VoiceTokenResponse token(
            @RequestParam("room") String room,
            @RequestParam(value = "identity", required = false) String identity) {
        if (room == null || room.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "room must not be blank");
        }
        String callerIdentity = (identity == null || identity.isBlank())
                ? "caller-" + UUID.randomUUID().toString().substring(0, 8)
                : identity;
        return tokenService.mint(room, callerIdentity);
    }
}
