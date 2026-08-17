CREATE TABLE IF NOT EXISTS site (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    timezone VARCHAR(64),
    api_key VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    address VARCHAR(500),
    addition TEXT,
    CONSTRAINT uk_data_source_site_name UNIQUE (site_id, name)
);

CREATE TABLE IF NOT EXISTS job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    status INT NOT NULL,
    crontab VARCHAR(128),
    timezone VARCHAR(64),
    retry_count INT,
    backoff_ms BIGINT,
    watermark_type VARCHAR(20),
    watermark_column VARCHAR(100),
    remarks TEXT,
    last_run_status VARCHAR(16),
    last_run_at TIMESTAMP,
    last_error TEXT,
    CONSTRAINT uk_job_site_name UNIQUE (site_id, name)
);

CREATE TABLE IF NOT EXISTS job_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    action_index INT NOT NULL,
    addition TEXT,
    CONSTRAINT uk_action_job_index UNIQUE (job_id, action_index)
);

CREATE TABLE IF NOT EXISTS template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(64) NOT NULL,
    config_json TEXT,
    CONSTRAINT uk_template_name_version UNIQUE (name, version)
);

CREATE TABLE IF NOT EXISTS watermark (
    job_key VARCHAR(64) PRIMARY KEY,
    wm_value BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS dead_letter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT,
    action_index INT,
    input_json TEXT,
    error TEXT,
    status VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS mapping_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    source_path VARCHAR(255) NOT NULL,
    target_name VARCHAR(255) NOT NULL,
    transform VARCHAR(32) NOT NULL,
    date_pattern VARCHAR(64),
    constant VARCHAR(255),
    enum_map TEXT,
    hit_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP,
    CONSTRAINT uk_mapping_history UNIQUE (site_id, direction, source_key, source_path, target_name)
);
