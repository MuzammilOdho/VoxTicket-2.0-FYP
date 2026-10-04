package com.voxticket.api.voice;

import com.voxticket.api.chat.ChatResponse;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationRuntime;
import com.voxticket.conversation.UserTurn;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The voice brain endpoint. The LiveKit worker POSTs one STT transcript per
 * caller turn; this builds the identical {@link UserTurn} the chat controller
 * builds - but on {@link Channel#PHONE} and with no caller-asserted phone -
 * and runs it through the shared {@link ConversationRuntime#processTurn}.
 *
 * <p>Intentionally thin, like ChatController: no business logic lives here.
 * The turn is fully blocking (same as chat); see the README for why true
 * streaming is a Phase 2, not a Phase 1, concern.
 */
@RestController
@RequestMapping("/api/v1/voice")
@Profile({"dev", "test"})
public class VoiceTurnController {

    private final ConversationRuntime conversationRuntime;

    public VoiceTurnController(ConversationRuntime conversationRuntime) {
        this.conversationRuntime = conversationRuntime;
    }

    @PostMapping("/turn")
    public ChatResponse turn(@Valid @RequestBody VoiceTurnRequest request) {
        UserTurn turn = new UserTurn(
                request.sessionId(), Channel.PHONE, request.message(), null, Instant.now(), Map.of());
        return ChatResponse.from(conversationRuntime.processTurn(turn));
    }
}
