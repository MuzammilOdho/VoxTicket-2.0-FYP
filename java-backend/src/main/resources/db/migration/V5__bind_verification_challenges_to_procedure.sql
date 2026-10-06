-- Phase 3 (OTP as Action Authorization). Binds each challenge to the exact
-- procedure and order it authorizes, not just customer+session+purpose.
-- Existing challenges are short-lived (a few minutes' expiry) and safe to
-- clear - this is dev-stage security state, not durable business data.
TRUNCATE TABLE verification_challenges;
ALTER TABLE verification_challenges ADD COLUMN procedure_id UUID NOT NULL;
ALTER TABLE verification_challenges ADD COLUMN order_number VARCHAR(30) NOT NULL;
CREATE INDEX ix_verification_challenges_procedure_id ON verification_challenges(procedure_id);