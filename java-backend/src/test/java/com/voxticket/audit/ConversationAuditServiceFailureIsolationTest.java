package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * Proves audit writes are best-effort - a broken repository must never break
 * the caller. The audit transaction is programmatic (REQUIRES_NEW) and the
 * try/catch covers the whole transaction including commit, so a persistence
 * failure rolls back only the audit transaction and is swallowed instead of
 * surfacing as an UnexpectedRollbackException or marking the caller's
 * business transaction rollback-only.
 */
class ConversationAuditServiceFailureIsolationTest {

    private PlatformTransactionManager txManager;
    private TransactionStatus txStatus;

    private ConversationAuditService serviceWithFailingSessionRepository() {
        ConversationSessionRecordRepository sessionRepository = mock(ConversationSessionRecordRepository.class);
        when(sessionRepository.findBySessionId(any())).thenThrow(new RuntimeException("db down"));
        txManager = mock(PlatformTransactionManager.class);
        txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);
        return new ConversationAuditService(sessionRepository, mock(ConversationMessageRecordRepository.class),
                mock(ConversationEventRecordRepository.class), txManager);
    }

    @Test
    void aRepositoryExceptionDuringSessionTouchNeverPropagates() {
        var service = serviceWithFailingSessionRepository();

        assertThatCode(() -> service.recordSessionTouch(ConversationSession.newSession("s1", Channel.CHAT)))
                .doesNotThrowAnyException();
        // The audit transaction alone was rolled back - the caller's transaction is untouched.
        verify(txManager).rollback(txStatus);
    }

    @Test
    void aRepositoryExceptionDuringMessageRecordingNeverPropagates() {
        var service = serviceWithFailingSessionRepository();

        assertThatCode(() -> service.recordMessage(ConversationSession.newSession("s1", Channel.CHAT), 1, MessageRole.USER, "hi"))
                .doesNotThrowAnyException();
        verify(txManager).rollback(txStatus);
    }

    @Test
    void aRepositoryExceptionDuringEventRecordingNeverPropagates() {
        var service = serviceWithFailingSessionRepository();

        assertThatCode(() -> service.recordEvent(ConversationSession.newSession("s1", Channel.CHAT), 1, ConversationEventType.TOOL_CALLED, "tool=test"))
                .doesNotThrowAnyException();
        verify(txManager).rollback(txStatus);
    }
}
