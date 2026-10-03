package com.voxticket.api.voice;

import tools.jackson.databind.ObjectMapper;
import com.voxticket.conversation.AssistantTurn;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.UserTurn;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Voice entry point into the shared conversation core - intentionally thin.
 * Everything it does is: authenticate the calling voice service, build a
 * channel-neutral {@link UserTurn} with {@link Channel#PHONE} from the STT
 * transcript, stream the turn's text deltas back as SSE, translate the final
 * {@link AssistantTurn} flags into the done event. No business logic lives
 * here, and none should ever be added here.
 *
 * <p>Unlike {@code ChatController} (dev/test only), this endpoint is active
 * in every profile: the service key is the impersonation guard instead of
 * profile gating. The key comparison is constant-time, and a blank
 * configured key rejects everything (fail closed).
 *
 * <p>SSE contract, one JSON object per event:
 * <ul>
 *   <li>{@code event: delta} {@code {"text":"..."}} - one native provider
 *       delta, forwarded unchanged; deterministic replies arrive as a single
 *       delta.</li>
 *   <li>{@code event: done} {@code {"sessionId":"...","requiresVerification":false,"requiresConfirmation":false}}
 *       - the turn completed; flags mirror {@link AssistantTurn}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceTurnController {

    private static final String SERVICE_KEY_HEADER = "X-Voice-Service-Key";
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final ConversationRuntime conversationRuntime;
    private final ObjectMapper objectMapper;
    private final String serviceKey;

    public VoiceTurnController(ConversationRuntime conversationRuntime, ObjectMapper objectMapper,
                               @Value("${voxticket.voice.service-key:}") String serviceKey) {
        this.conversationRuntime = conversationRuntime;
        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey;
    }

    @PostMapping(value = "/turn/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurn(
            @RequestHeader(value = SERVICE_KEY_HEADER, required = false) String serviceKeyHeader,
            @Valid @RequestBody VoiceTurnRequest request) {
        if (!isAuthorized(serviceKeyHeader)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid voice service key");
        }
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "voice-" + UUID.randomUUID();
        }
        UserTurn turn = new UserTurn(sessionId, Channel.PHONE, request.text(), request.callerPhone(), Instant.now(), Map.of());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            AssistantTurn result = conversationRuntime.streamTurn(turn,
                    delta -> send(emitter, "delta", Map.of("text", delta)));
            send(emitter, "done", Map.of(
                    "sessionId", result.conversationState().sessionId(),
                    "requiresVerification", result.requiresVerification(),
                    "requiresConfirmation", result.requiresConfirmation()));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private boolean isAuthorized(String headerValue) {
        if (serviceKey == null || serviceKey.isBlank() || headerValue == null) {
            return false;
        }
        return MessageDigest.isEqual(
                serviceKey.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8));
    }

    private void send(SseEmitter emitter, String eventName, Map<String, ?> data) {
        try {
            // Jackson 3: writeValueAsString throws the unchecked JacksonException, so only
            // SseEmitter.send contributes the checked IOException handled below.
            emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
