package com.voxticket.api.chat;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.UserTurn;
import com.voxticket.observability.TraceIds;
import com.voxticket.observability.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec §3.2. Development/testing entry point into the shared conversation
 * core - intentionally thin. Everything it does is: build a channel-neutral
 * UserTurn from the request, hand it to ConversationRuntime, translate the
 * resulting AssistantTurn into a response DTO. No business logic lives
 * here, and none should ever be added here - Phase 11's voice path will
 * build the identical UserTurn from STT output and call the exact same
 * ConversationRuntime.processTurn(...).
 *
 * <p>Gated to dev/test profiles per the resolved chat-security decision:
 * this endpoint lets the caller assert an arbitrary customerPhone, which
 * must never become a public impersonation mechanism. A production-facing
 * chat surface, if one is ever needed, is a different, separately
 * authenticated controller - not this one with the gate removed.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Profile({"dev", "test"})
public class ChatController {

    private final ConversationRuntime conversationRuntime;

    public ChatController(ConversationRuntime conversationRuntime) {
        this.conversationRuntime = conversationRuntime;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? "chat-" + UUID.randomUUID()
                : request.sessionId();

        UserTurn turn = new UserTurn(sessionId, Channel.CHAT, request.message(), request.customerPhone(), Instant.now(), traceMetadata(httpRequest));
        var assistantTurn = conversationRuntime.processTurn(turn);
        return ChatResponse.from(assistantTurn);
    }

    /**
     * P0 (correlation). Propagates the request trace ID (resolved by
     * {@link TraceIdFilter} from the {@code traceparent} header) into the
     * turn's provider metadata. A missing attribute (e.g. unit tests without
     * the filter) degrades to a fresh ID - never null, never breaking the
     * turn.
     */
    private static Map<String, String> traceMetadata(HttpServletRequest httpRequest) {
        Object attribute = httpRequest == null ? null : httpRequest.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE_TRACE_ID);
        String traceId = (attribute instanceof String s && !s.isBlank()) ? s : TraceIds.newTraceId();
        return Map.of(TraceIds.METADATA_TRACE_ID, traceId);
    }
}