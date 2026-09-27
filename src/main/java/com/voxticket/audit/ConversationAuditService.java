package com.voxticket.audit;

import com.voxticket.conversation.ConversationSession;
import com.voxticket.conversation.MessageRole;
import com.voxticket.persistence.entity.ConversationEventRecord;
import com.voxticket.persistence.entity.ConversationMessageRecord;
import com.voxticket.persistence.entity.ConversationSessionRecord;
import com.voxticket.persistence.entity.enums.ConversationEventType;
import com.voxticket.persistence.repository.ConversationEventRecordRepository;
import com.voxticket.persistence.repository.ConversationMessageRecordRepository;
import com.voxticket.persistence.repository.ConversationSessionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 8 (Conversation Audit). The single injection point for durable
 * audit trail - separate from the in-memory ConversationSession that
 * drives the live conversation, and separate from TurnMetrics (aggregate
 * Micrometer counters). Every public method here is internally exception-
 * safe: a failed audit write must never break the actual conversation,
 * matching the same principle already applied to token-usage extraction
 * elsewhere in this codebase.
 *
 * <p>NEVER pass into detail: OTP values, secrets, chain-of-thought, hidden/
 * provider reasoning, or the raw system prompt. Every call site in this
 * codebase only ever passes short structured summaries (tool names,
 * outcome codes, model tiers) - never model-generated free text.
 */
@Service
public class ConversationAuditService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAuditService.class);
    private static final int MAX_DETAIL_LENGTH = 500;

    private final ConversationSessionRecordRepository sessionRepository;
    private final ConversationMessageRecordRepository messageRepository;
    private final ConversationEventRecordRepository eventRepository;
    private final TransactionTemplate auditTransaction;

    public ConversationAuditService(
            ConversationSessionRecordRepository sessionRepository,
            ConversationMessageRecordRepository messageRepository,
            ConversationEventRecordRepository eventRepository,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.eventRepository = eventRepository;
        this.auditTransaction = new TransactionTemplate(transactionManager);
        // Independent transaction: an audit-write failure rolls back only the
        // audit transaction itself and can never mark the caller's business
        // transaction rollback-only.
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Best-effort and non-throwing: a failed audit write must never break the
     * actual conversation. The try/catch covers the whole programmatic
     * transaction (repository calls AND commit), so a persistence exception -
     * or a commit-time failure - is logged and swallowed instead of surfacing
     * as an UnexpectedRollbackException or poisoning the caller's transaction.
     */
    public void recordSessionTouch(ConversationSession session) {
        try {
            auditTransaction.executeWithoutResult(status -> {
                ConversationSessionRecord record = findOrCreateSession(session);
                record.touch(session.getCustomerIdentity().customerId(), session.getCustomerIdentity().assuranceLevel(), session.isEscalated());
                sessionRepository.save(record);
            });
        } catch (Exception e) {
            log.warn("event=audit_write_failed table=conversation_sessions sessionId={} errorType={}",
                    session.getSessionId(), e.getClass().getSimpleName());
        }
    }

    /** Independent transaction - see {@link #recordSessionTouch(ConversationSession)}. */
    public void recordMessage(ConversationSession session, int turnNumber, MessageRole role, String text) {
        try {
            auditTransaction.executeWithoutResult(status -> {
                ConversationSessionRecord record = findOrCreateSession(session);
                messageRepository.save(new ConversationMessageRecord(record, turnNumber, role, text));
            });
        } catch (Exception e) {
            log.warn("event=audit_write_failed table=conversation_messages sessionId={} errorType={}",
                    session.getSessionId(), e.getClass().getSimpleName());
        }
    }

    /** Independent transaction - see {@link #recordSessionTouch(ConversationSession)}. */
    public void recordEvent(ConversationSession session, Integer turnNumber, ConversationEventType type, String detail) {
        try {
            auditTransaction.executeWithoutResult(status -> {
                ConversationSessionRecord record = findOrCreateSession(session);
                eventRepository.save(new ConversationEventRecord(record, turnNumber, type, truncate(detail)));
            });
        } catch (Exception e) {
            log.warn("event=audit_write_failed table=conversation_events eventType={} sessionId={} errorType={}",
                    type, session.getSessionId(), e.getClass().getSimpleName());
        }
    }

    private ConversationSessionRecord findOrCreateSession(ConversationSession session) {
        return sessionRepository.findBySessionId(session.getSessionId())
                .orElseGet(() -> sessionRepository.save(new ConversationSessionRecord(
                        session.getSessionId(), session.getChannel(), session.getCustomerIdentity().assuranceLevel())));
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
    }
}
