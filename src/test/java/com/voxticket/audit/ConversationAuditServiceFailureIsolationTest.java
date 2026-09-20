package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import org.junit.jupiter.api.Test;

/** Proves audit writes are best-effort - a broken repository must never break the caller. */
class ConversationAuditServiceFailureIsolationTest {

    private ConversationAuditService serviceWithFailingSessionRepository() {
        ConversationSessionRecordRepository sessionRepository = mock(ConversationSessionRecordRepository.class);
        when(sessionRepository.findBySessionId(any())).thenThrow(new RuntimeException("db down"));
        return new ConversationAuditService(sessionRepository, mock(ConversationMessageRecordRepository.class), mock(ConversationEventRecordRepository.class));
    }

    @Test
    void aRepositoryExceptionDuringSessionTouchNeverPropagates() {
        assertThatCode(() -> serviceWithFailingSessionRepository().recordSessionTouch(ConversationSession.newSession("s1", Channel.CHAT)))
                .doesNotThrowAnyException();
    }

    @Test
    void aRepositoryExceptionDuringMessageRecordingNeverPropagates() {
        assertThatCode(() -> serviceWithFailingSessionRepository().recordMessage(ConversationSession.newSession("s1", Channel.CHAT), 1, MessageRole.USER, "hi"))
                .doesNotThrowAnyException();
    }

    @Test
    void aRepositoryExceptionDuringEventRecordingNeverPropagates() {
        assertThatCode(() -> serviceWithFailingSessionRepository().recordEvent(ConversationSession.newSession("s1", Channel.CHAT), 1, ConversationEventType.TOOL_CALLED, "tool=test"))
                .doesNotThrowAnyException();
    }
}