-- Phase 6: RAG (spec §43). Schema matches exactly what Spring AI's
-- PgVectorStore expects by default - Flyway is the schema authority here
-- too (initialize-schema stays off, consistent with every other table in
-- this project).

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS vector_store (
                                            id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(768)  -- nomic-embed-text's output dimension
    );

CREATE INDEX IF NOT EXISTS vector_store_embedding_index ON vector_store USING HNSW (embedding vector_cosine_ops);