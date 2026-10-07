-- V10: deduplicate voice turn metrics on worker retry.
--
-- The Python worker POSTs telemetry batches with at-least-once semantics: if
-- it does not receive the 202 (crash, timeout, disconnect), it resends the
-- same batch. Without a uniqueness guard, a retried batch double-inserts turn
-- metric rows. This constraint makes (call_id, turn_number) unique; the
-- writer's INSERT uses ON CONFLICT DO NOTHING so retries are idempotent.
--
-- Existing duplicates (if any) must be removed before this migration runs.
-- The statement below keeps the earliest-inserted row per (call_id,
-- turn_number) and is safe to run on an empty table.

DELETE FROM voice_call_turn_metrics a
USING voice_call_turn_metrics b
WHERE a.call_id = b.call_id
  AND a.turn_number = b.turn_number
  AND a.created_at > b.created_at;

ALTER TABLE voice_call_turn_metrics
    ADD CONSTRAINT ux_voice_turn_metrics_call_turn UNIQUE (call_id, turn_number);
