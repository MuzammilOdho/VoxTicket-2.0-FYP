package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * P1: proves the audit path is best-effort and non-blocking. A bus that
 * throws - or a full queue - must never break the caller. Publish failures
 * are swallowed by the service; overflow is dropped and counted by the bus.
 */
class ConversationAuditServiceFailureIsolationTest {

    private static ConversationAuditService serviceWithThrowingBus() {
        AuditEventBus bus = mock(AuditEventBus.class);
        doThrow(new RuntimeException("bus exploded")).when(bus).publish(any(AuditEvent.class));
        return new ConversationAuditService(bus);
    }

    @Test
    void aPublishExceptionDuringSessionTouchNeverPropagates() {
        var service = serviceWithThrowingBus();

        assertThatCode(() -> service.recordSessionTouch(ConversationSession.newSession("s1", Channel.CHAT)))
                .doesNotThrowAnyException();
    }

    @Test
    void aPublishExceptionDuringMessageRecordingNeverPropagates() {
        var service = serviceWithThrowingBus();

        assertThatCode(() -> service.recordMessage(ConversationSession.newSession("s1", Channel.CHAT), 1, MessageRole.USER, "hi"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPublishExceptionDuringEventRecordingNeverPropagates() {
        var service = serviceWithThrowingBus();

        assertThatCode(() -> service.recordEvent(ConversationSession.newSession("s1", Channel.CHAT), 1, ConversationEventType.TOOL_CALLED, "tool=test"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPublishExceptionDuringTurnCompletionNeverPropagates() {
        var service = serviceWithThrowingBus();

        assertThatCode(() -> service.recordTurnCompletion(ConversationSession.newSession("s1", Channel.CHAT), 1, "normal", false))
                .doesNotThrowAnyException();
    }

    @Test
    void queueOverflowDropsTelemetryAndNeverThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventBus bus = new AuditEventBus(2, registry);
        var service = new ConversationAuditService(bus);

        var session = ConversationSession.newSession("s2", Channel.CHAT);
        // Capacity is 2: the third publish overflows - dropped and counted, not thrown.
        assertThatCode(() -> {
            service.recordSessionTouch(session);
            service.recordMessage(session, 1, MessageRole.USER, "one");
            service.recordMessage(session, 1, MessageRole.ASSISTANT, "two");
        }).doesNotThrowAnyException();

        assertThat(bus.depth()).isEqualTo(2);
        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "overflow").count()).isEqualTo(1.0);
    }
}
