CREATE TABLE IF NOT EXISTS knowledge_document (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    file_name      VARCHAR(255) NOT NULL,
    knowledge_base VARCHAR(64)  NOT NULL DEFAULT 'default',
    content_type   VARCHAR(128),
    file_size      BIGINT       DEFAULT 0,
    chunk_count    INT          DEFAULT 0,
    status         VARCHAR(32)  NOT NULL,
    error_message  VARCHAR(2000),
    storage_path   VARCHAR(512),
    created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON knowledge_document (knowledge_base);
