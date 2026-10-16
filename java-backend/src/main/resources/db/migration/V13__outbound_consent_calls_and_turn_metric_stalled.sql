-- P4.1: outbound-call trigger tables.
-- P4.3: worker mid-stream stall flag (P1.4) on the per-turn voice metrics.

CREATE TABLE outbound_consent (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID NOT NULL,
    consented     BOOLEAN NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_outbound_consent_customer UNIQUE (customer_id)
);

CREATE TABLE outbound_calls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id         UUID NOT NULL,
    customer_phone  VARCHAR(20) NOT NULL,
    reason          VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_outbound_calls_call_id UNIQUE (call_id)
);
CREATE UNIQUE INDEX ux_outbound_calls_idempotency
    ON outbound_calls (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE voice_call_turn_metrics
    ADD COLUMN stalled BOOLEAN;
