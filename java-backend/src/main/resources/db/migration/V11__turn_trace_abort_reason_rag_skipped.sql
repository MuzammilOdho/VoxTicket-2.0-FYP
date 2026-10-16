-- P0.5: abort classification and RAG degradation flag on the turn trace.
ALTER TABLE conversation_turn_traces
    ADD COLUMN abort_reason VARCHAR(30),
    ADD COLUMN rag_skipped BOOLEAN;
