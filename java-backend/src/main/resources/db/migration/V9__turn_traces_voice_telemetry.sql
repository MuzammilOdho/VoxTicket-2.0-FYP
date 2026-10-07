-- V9: Observability - per-turn decision traces, voice telemetry, session lifecycle.
--
-- Design rules (from the approved architecture):
--   * These tables are TELEMETRY, not business state. They are written by the
--     asynchronous AuditBatchWriter only - never on the conversation critical
--     path. Readers are admin queries only.
--   * Per-turn decision traces are one row per turn in conversation_turn_traces.
--     Filterable/sortable fields are typed columns; overflow detail is JSONB.
--   * NEVER persist here: OTP values, secrets, system prompts,
--     chain-of-thought/reasoning, full RAG document contents.
--   * conversation_events.detail keeps its 500-char contract; trace_id links
--     an event row to its turn trace row.

-- Session lifecycle support: turn count + last outcome let the admin derive
-- ACTIVE / COMPLETED / ABORTED / EXPIRED without extra writes.
ALTER TABLE conversation_sessions
    ADD COLUMN turn_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation_sessions
    ADD COLUMN last_turn_outcome VARCHAR(40);

-- Correlation: link audit events to their turn trace.
ALTER TABLE conversation_events
    ADD COLUMN trace_id VARCHAR(32);

-- One row per conversation turn: the AI decision trace.
-- session_id + turn_number is the natural key (a turn is processed once).
CREATE TABLE conversation_turn_traces (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES conversation_sessions(id),
    turn_number         INTEGER NOT NULL,
    channel             VARCHAR(10) NOT NULL,
    trace_id            VARCHAR(32) NOT NULL,
    parent_trace_id     VARCHAR(32),
    started_at          TIMESTAMPTZ NOT NULL,
    ended_at            TIMESTAMPTZ,
    outcome             VARCHAR(40),
    aborted             BOOLEAN NOT NULL DEFAULT FALSE,

    -- Stage latencies in milliseconds (NULL = not measured / not applicable).
    normalize_ms        DOUBLE PRECISION,
    guard_ms            DOUBLE PRECISION,
    routing_ms          DOUBLE PRECISION,
    llm_ttft_ms         DOUBLE PRECISION,
    llm_total_ms        DOUBLE PRECISION,
    rag_ms              DOUBLE PRECISION,
    tool_ms             DOUBLE PRECISION,

    -- Prompt guard.
    guard_suspicious    BOOLEAN,
    guard_category      VARCHAR(80),
    guard_implementation VARCHAR(20),
    guard_fallback      BOOLEAN,

    -- Language / intent / routing.
    language            VARCHAR(10),
    intent              VARCHAR(80),
    intent_signals      VARCHAR(500),
    routing_strategy    VARCHAR(20),
    tier                VARCHAR(10),
    routing_reason      VARCHAR(60),
    semantic_margin     DOUBLE PRECISION,

    -- Model + tokens.
    provider            VARCHAR(20),
    model               VARCHAR(80),
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,

    -- RAG: cache flag + per-document id/category/similarity (never full text).
    rag_cache_hit       BOOLEAN,
    rag_docs            JSONB,

    -- Tools: JSON array of {name, result, durationMs}.
    tools               JSONB,

    -- Procedure.
    procedure_type         VARCHAR(40),
    procedure_status       VARCHAR(40),
    procedure_outcome_code VARCHAR(60),

    -- Error classification (NULL when the turn did not error).
    error_code          VARCHAR(60),

    -- Overflow detail (structured, redacted). Never OTP/secrets/prompts/reasoning.
    decision            JSONB,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ux_turn_traces_session_turn UNIQUE (session_id, turn_number)
);
CREATE INDEX ix_turn_traces_session_id ON conversation_turn_traces(session_id);
CREATE INDEX ix_turn_traces_trace_id ON conversation_turn_traces(trace_id);
CREATE INDEX ix_turn_traces_ended_at ON conversation_turn_traces(ended_at DESC);
CREATE INDEX ix_turn_traces_outcome ON conversation_turn_traces(outcome);
CREATE INDEX ix_turn_traces_channel ON conversation_turn_traces(channel);

-- Voice call lifecycle: one row per LiveKit call (room == Java sessionId).
CREATE TABLE voice_call_sessions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL REFERENCES conversation_sessions(id),
    room             VARCHAR(200) NOT NULL,
    trace_id         VARCHAR(32),
    stt_provider     VARCHAR(40),
    stt_model        VARCHAR(80),
    tts_provider     VARCHAR(40),
    tts_model        VARCHAR(80),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    outcome          VARCHAR(20),
    barge_in_count   INTEGER NOT NULL DEFAULT 0,
    disconnect_reason VARCHAR(80),
    worker_id        VARCHAR(80),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ux_voice_call_sessions_room UNIQUE (room)
);
CREATE INDEX ix_voice_call_sessions_session_id ON voice_call_sessions(session_id);
CREATE INDEX ix_voice_call_sessions_started_at ON voice_call_sessions(started_at DESC);

-- Voice per-turn stage latencies, emitted by the Python worker.
CREATE TABLE voice_call_turn_metrics (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id            UUID NOT NULL REFERENCES voice_call_sessions(id),
    turn_number        INTEGER NOT NULL,
    trace_id           VARCHAR(32),
    stt_latency_ms     DOUBLE PRECISION,
    brain_ttft_ms      DOUBLE PRECISION,
    tts_first_audio_ms DOUBLE PRECISION,
    e2e_ms             DOUBLE PRECISION,
    aborted            BOOLEAN NOT NULL DEFAULT FALSE,
    barge_in           BOOLEAN NOT NULL DEFAULT FALSE,
    stt_language       VARCHAR(10),
    error              VARCHAR(120),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_voice_turn_metrics_call_id ON voice_call_turn_metrics(call_id);

-- Worker heartbeats: last-seen per worker id (upserted by the writer).
CREATE TABLE voice_worker_heartbeats (
    worker_id     VARCHAR(80) PRIMARY KEY,
    stt_provider  VARCHAR(40),
    tts_provider  VARCHAR(40),
    active_rooms  INTEGER NOT NULL DEFAULT 0,
    last_seen_at  TIMESTAMPTZ NOT NULL
);
