-- P3.1: prompt-template identity on the turn trace. Every turn pins the
-- system-prompt version (voxticket.prompt.version) and the SHA-256 of the
-- loaded template bytes, so a trace identifies the prompt byte-for-byte.
ALTER TABLE conversation_turn_traces
    ADD COLUMN prompt_version VARCHAR(20),
    ADD COLUMN prompt_content_hash VARCHAR(64);
