package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.persistence.entity.ConversationSessionRecord;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ConversationAuditServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private ConversationAuditService auditService;
    @Autowired
    private ConversationSessionRecordRepository sessionRepository;
    @Autowired
    private ConversationMessageRecordRepository messageRepository;
    @Autowired
    private ConversationEventRecordRepository eventRepository;

    private ConversationSession session;

    @BeforeEach
    void setUp() {
        session = ConversationSession.newSession("audit-session-" + UUID.randomUUID(), Channel.CHAT);
    }

    @Test
    void recordingATurnCreatesASessionRecordOnFirstUse() {
        auditService.recordSessionTouch(session);

        ConversationSessionRecord record = sessionRepository.findBySessionId(session.getSessionId()).orElseThrow();
        assertThat(record.getChannel()).isEqualTo(Channel.CHAT);
        assertThat(record.getIdentityAssurance()).isEqualTo(IdentityAssurance.ANONYMOUS);
    }

    @Test
    void resolvingIdentityAndTouchingAgainUpdatesTheSameRecordRatherThanCreatingANewOne() {
        auditService.recordSessionTouch(session);
        session.applyResolvedIdentity(new CustomerIdentity(UUID.randomUUID(), IdentityAssurance.PHONE_MATCHED, "+923001234567"));

        auditService.recordSessionTouch(session);

        assertThat(sessionRepository.findAll()).filteredOn(r -> r.getSessionId().equals(session.getSessionId())).hasSize(1);
        ConversationSessionRecord record = sessionRepository.findBySessionId(session.getSessionId()).orElseThrow();
        assertThat(record.getIdentityAssurance()).isEqualTo(IdentityAssurance.PHONE_MATCHED);
    }

    @Test
    void recordingAMessagePersistsItLinkedToTheSession() {
        auditService.recordMessage(session, 1, MessageRole.USER, "Where is my order?");

        ConversationSessionRecord record = sessionRepository.findBySessionId(session.getSessionId()).orElseThrow();
        assertThat(messageRepository.findBySessionIdOrderByTurnNumberAsc(record.getId())).hasSize(1)
                .first().satisfies(m -> {
                    assertThat(m.getRole()).isEqualTo(MessageRole.USER);
                    assertThat(m.getText()).isEqualTo("Where is my order?");
                });
    }

    @Test
    void recordingAMessageBeforeAnySessionTouchStillCreatesTheSessionImplicitly() {
        auditService.recordMessage(session, 1, MessageRole.ASSISTANT, "Sure, one moment.");

        assertThat(sessionRepository.findBySessionId(session.getSessionId())).isPresent();
    }

    @Test
    void recordingAnEventTruncatesOverlyLongDetail() {
        String longDetail = "x".repeat(1000);

        auditService.recordEvent(session, 1, ConversationEventType.TOOL_CALLED, longDetail);

        ConversationSessionRecord record = sessionRepository.findBySessionId(session.getSessionId()).orElseThrow();
        var events = eventRepository.findBySessionIdOrderByCreatedAtAsc(record.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDetail()).hasSize(500);
    }

    @Test
    void recordingWithNoTurnNumberStillSucceeds() {
        assertThatCode(() -> auditService.recordEvent(session, null, ConversationEventType.SAFETY_BLOCKED, "category=INSTRUCTION_OVERRIDE"))
                .doesNotThrowAnyException();
    }
}