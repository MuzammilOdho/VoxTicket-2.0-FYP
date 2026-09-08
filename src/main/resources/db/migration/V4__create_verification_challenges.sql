-- Phase 9: OTP step-up verification (spec §9). A real table, unlike
-- ProcedureState/PendingAction which stay in-memory - this is genuine
-- security state worth an audit trail.

CREATE TABLE verification_challenges (
                                         id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                         customer_id         UUID NOT NULL REFERENCES customers(id),
                                         session_id          VARCHAR(100) NOT NULL,
                                         purpose             VARCHAR(20) NOT NULL,
                                         delivery_channel    VARCHAR(10) NOT NULL,
                                         masked_destination  VARCHAR(255) NOT NULL,
                                         otp_hash            VARCHAR(128) NOT NULL,
                                         otp_salt            VARCHAR(64) NOT NULL,
                                         created_at          TIMESTAMPTZ NOT NULL,
                                         expires_at          TIMESTAMPTZ NOT NULL,
                                         attempts            INTEGER NOT NULL DEFAULT 0,
                                         consumed            BOOLEAN NOT NULL DEFAULT FALSE,
                                         verified            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_verification_challenges_customer_id ON verification_challenges(customer_id);
CREATE INDEX ix_verification_challenges_session_id ON verification_challenges(session_id);