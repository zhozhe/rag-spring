CREATE TABLE IF NOT EXISTS knowledge_document (
    id             VARCHAR(64)  NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    knowledge_base VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '知识库标识',
    content_type   VARCHAR(128) DEFAULT NULL,
    file_size      BIGINT       DEFAULT 0,
    chunk_count    INT          DEFAULT 0,
    status         VARCHAR(32)  NOT NULL COMMENT 'PROCESSING/READY/FAILED',
    error_message  VARCHAR(2000) DEFAULT NULL,
    storage_path   VARCHAR(512) DEFAULT NULL,
    created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doc_kb (knowledge_base, status),
    KEY idx_doc_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
