-- Switches the embedding model from nomic-embed-text (768-dim) to bge-m3
-- (1024-dim) for proper English/Urdu/Roman-Urdu/code-switched retrieval.
-- V2 has already run in this project, so this is a new migration rather
-- than an edit to it - Flyway checksums applied migrations, and editing
-- one after the fact breaks every environment that already ran it.
--
-- This truncates existing vector_store content. That's acceptable at this
-- stage: knowledge-base content is fully reproducible by re-running
-- KnowledgeIngestionService, so there is nothing here worth preserving
-- across the dimension change.

TRUNCATE TABLE vector_store;
DROP INDEX IF EXISTS vector_store_embedding_index;
ALTER TABLE vector_store DROP COLUMN embedding;
ALTER TABLE vector_store ADD COLUMN embedding VECTOR(1024);
CREATE INDEX IF NOT EXISTS vector_store_embedding_index ON vector_store USING HNSW (embedding vector_cosine_ops);