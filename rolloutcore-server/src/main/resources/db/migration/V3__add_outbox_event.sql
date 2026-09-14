CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    project_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    environment_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    flag_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    version BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_version CHECK (version >= 0),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    INDEX ix_outbox_pending (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
