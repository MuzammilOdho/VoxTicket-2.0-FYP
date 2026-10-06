-- Phase 8 (Conversation Audit). Persisted independently from the in-memory
-- ConversationSession that drives the live conversation (spec §7 still
-- holds) - this is durable audit trail for debugging/evaluation only, and
-- is never reconstructed back into a live session.
CREATE TABLE conversation_sessions (
                                       id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                       session_id          VARCHAR(100) NOT NULL UNIQUE,
                                       channel             VARCHAR(10) NOT NULL,
                                       customer_id         UUID,
                                       identity_assurance  VARCHAR(20) NOT NULL,
                                       escalated           BOOLEAN NOT NULL DEFAULT FALSE,
                                       started_at          TIMESTAMPTZ NOT NULL,
                                       last_activity_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_conversation_sessions_customer_id ON conversation_sessions(customer_id);

CREATE TABLE conversation_messages (
                                       id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                       session_id      UUID NOT NULL REFERENCES conversation_sessions(id),
                                       turn_number     INTEGER NOT NULL,
                                       role            VARCHAR(10) NOT NULL,
                                       text            TEXT NOT NULL,
                                       created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_conversation_messages_session_id ON conversation_messages(session_id);

CREATE TABLE conversation_events (
                                     id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     session_id      UUID NOT NULL REFERENCES conversation_sessions(id),
                                     turn_number     INTEGER,
                                     event_type      VARCHAR(30) NOT NULL,
                                     detail          VARCHAR(500),
                                     created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_conversation_events_session_id ON conversation_events(session_id);
CREATE INDEX ix_conversation_events_event_type ON conversation_events(event_type);