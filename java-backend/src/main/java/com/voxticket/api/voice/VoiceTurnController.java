package com.voxticket.api.voice;

import com.voxticket.api.chat.ChatResponse;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.SessionBusyException;
import com.voxticket.conversation.SessionStore;
import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.UserTurn;
import com.voxticket.observability.TraceIds;
import com.voxticket.observability.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The voice brain endpoint. The LiveKit worker POSTs one STT transcript per
 * caller turn; this builds the identical {@link UserTurn} the chat controller
 * builds - but on {@link Channel#PHONE} and with no caller-asserted phone -
 * and runs it through the shared {@link ConversationRuntime#processTurn}.
 *
 * <p>Intentionally thin, like ChatController: no business logic lives here.
 */
@RestController
@RequestMapping("/api/v1/voice")
@Profile({"dev", "test"})
public class VoiceTurnController {

    private static final Logger log = LoggerFactory.getLogger(VoiceTurnController.class);

    private final ConversationRuntime conversationRuntime;
    private final SessionStore sessionStore;

    public VoiceTurnController(ConversationRuntime conversationRuntime, SessionStore sessionStore) {
        this.conversationRuntime = conversationRuntime;
        this.sessionStore = sessionStore;
    }

    @PostMapping("/turn")
    public ChatResponse turn(@Valid @RequestBody VoiceTurnRequest request, HttpServletRequest httpRequest) {
        UserTurn turn = new UserTurn(
                request.sessionId(), Channel.PHONE, request.message(), null, Instant.now(), turnMetadata(httpRequest, request.sessionId()));
        return ChatResponse.from(conversationRuntime.processTurn(turn));
    }

    /**
     * Streaming variant of {@link #turn()}: Server-Sent Events, one
     * {@code data: {"delta": "..."}} per LLM token batch and a final
     * {@code data: {"done": true}}. Deterministic turns (OTP, confirmations,
     * guard rejections) arrive as a single delta - they never touch the LLM.
     *
     * <p>Client disconnect (barge-in / hang-up) surfaces as an
     * {@link IOException} on the next emit, which becomes a
     * {@link TurnAbortedException}: the runtime drops the partial reply and
     * releases the per-session lock immediately.
     *
     * <p>If the session is still locked by the previous (aborted) turn when
     * this turn arrives, the lock wait budget expires and the request fails
     * with HTTP 429 ({@link SessionBusyException}) instead of stalling
     * behind the obsolete turn; the voice worker retries on 429.
     */
    @PostMapping("/turn/stream")
    public SseEmitter turnStream(@Valid @RequestBody VoiceTurnRequest request, HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        UserTurn turn = new UserTurn(
                request.sessionId(), Channel.PHONE, request.message(), null, Instant.now(), turnMetadata(httpRequest, request.sessionId()));
        try {
            conversationRuntime.processTurnStream(turn, delta -> {
                try {
                    emitter.send(SseEmitter.event().data(Map.of("delta", delta)));
                } catch (IOException | IllegalStateException e) {
                    throw new TurnAbortedException(e);
                }
            });
            try {
                emitter.send(SseEmitter.event().data(Map.of("done", true)));
            } catch (IOException | IllegalStateException e) {
                // Hung up after the last delta: the same abort, just quieter.
                throw new TurnAbortedException(e);
            }
            emitter.complete();
        } catch (SessionBusyException busy) {
            // The session is still locked by the previous (barge-in-aborted)
            // turn, which has not finished unwinding. Rethrown so the
            // @ResponseStatus on the exception maps it to 429 for the voice
            // worker to retry; the generic catch below must not turn it into
            // a stream error, which would hide the retryable signal.
            throw busy;
        } catch (TurnAbortedException aborted) {
            log.debug("voice turn stream aborted by client disconnect");
            emitter.complete();
        } catch (Exception e) {
            log.error("voice turn stream failed", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * P0 (correlation). Propagates the request trace ID (resolved by
     * {@link TraceIdFilter} from the {@code traceparent} header) into the
     * turn's provider metadata so the runtime, audit rows, and logs all share
     * it. A missing attribute (e.g. unit tests without the filter) degrades
     * to a fresh ID - never null, never breaking the turn.
     *
     * <p>Also mints the turn's voice barge-in generation: each new voice
     * turn increments the session's generation, so the runtime can abort a
     * superseded turn cooperatively (releasing the session lock within one
     * token) instead of letting it run to completion behind the barge-in.
     */
    private Map<String, String> turnMetadata(HttpServletRequest httpRequest, String sessionId) {
        Object attribute = httpRequest == null ? null : httpRequest.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE_TRACE_ID);
        String traceId = (attribute instanceof String s && !s.isBlank()) ? s : TraceIds.newTraceId();
        long generation = sessionStore.nextVoiceGeneration(sessionId);
        return Map.of(TraceIds.METADATA_TRACE_ID, traceId,
                ConversationRuntime.METADATA_VOICE_GENERATION, String.valueOf(generation));
    }
}
