-- MySQL 8.0.16+ (enforced CHECK constraints). Keys are case-sensitive.
CREATE TABLE ff_project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_project_key UNIQUE (project_key),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ff_environment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    env_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_environment_key UNIQUE (project_id, env_key),
    CONSTRAINT fk_environment_project FOREIGN KEY (project_id) REFERENCES ff_project(id),
    CONSTRAINT ck_environment_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ff_feature_flag (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    flag_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_flag_key UNIQUE (project_id, flag_key),
    CONSTRAINT fk_flag_project FOREIGN KEY (project_id) REFERENCES ff_project(id),
    CONSTRAINT ck_flag_type CHECK (value_type IN ('BOOLEAN', 'STRING', 'NUMBER', 'JSON')),
    CONSTRAINT ck_flag_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ff_flag_variant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flag_id BIGINT NOT NULL,
    variant_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    value_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_variant_key UNIQUE (flag_id, variant_key),
    -- Supports the composite FK that prevents a config choosing another flag's variant.
    CONSTRAINT uk_variant_owner UNIQUE (flag_id, id),
    CONSTRAINT fk_variant_flag FOREIGN KEY (flag_id) REFERENCES ff_feature_flag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ff_flag_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    flag_id BIGINT NOT NULL,
    environment_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_variant_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_config_flag_environment UNIQUE (flag_id, environment_id),
    CONSTRAINT fk_config_flag FOREIGN KEY (flag_id) REFERENCES ff_feature_flag(id),
    CONSTRAINT fk_config_environment FOREIGN KEY (environment_id) REFERENCES ff_environment(id),
    CONSTRAINT fk_config_variant FOREIGN KEY (flag_id, default_variant_id) REFERENCES ff_flag_variant(flag_id, id),
    CONSTRAINT ck_config_version CHECK (version >= 0),
    CONSTRAINT ck_config_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ff_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    environment_id BIGINT NULL,
    flag_id BIGINT NULL,
    operator_name VARCHAR(100) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    before_json JSON NULL,
    after_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES ff_project(id),
    CONSTRAINT fk_audit_environment FOREIGN KEY (environment_id) REFERENCES ff_environment(id),
    CONSTRAINT fk_audit_flag FOREIGN KEY (flag_id) REFERENCES ff_feature_flag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
