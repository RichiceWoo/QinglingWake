-- 生产环境由部署方预先安装 vector 扩展；应用不会自动执行 CREATE EXTENSION。
CREATE TABLE IF NOT EXISTS memories (
    id VARCHAR(16) PRIMARY KEY,
    session_id TEXT NOT NULL,
    routing_key TEXT NOT NULL,
    user_message TEXT NOT NULL,
    assistant_reply TEXT NOT NULL,
    summary TEXT NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    turn_ts TIMESTAMPTZ NOT NULL,
    summary_vec vector(1024) NOT NULL,
    message_vec vector(1024) NOT NULL,
    search_text TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memories_routing_turn
    ON memories (routing_key, turn_ts DESC);

CREATE INDEX IF NOT EXISTS idx_memories_summary_vec_hnsw
    ON memories USING hnsw (summary_vec vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_memories_message_vec_hnsw
    ON memories USING hnsw (message_vec vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_memories_search_text_gin
    ON memories USING gin (to_tsvector('simple', search_text));
