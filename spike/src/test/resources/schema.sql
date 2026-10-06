CREATE TABLE IF NOT EXISTS site (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    timezone VARCHAR(64),
    api_key VARCHAR(255),
    suggester VARCHAR(16)
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
    direction VARCHAR(16),
    crontab VARCHAR(128),
    schedule_type VARCHAR(16),
    interval_value INT,
    interval_unit VARCHAR(16),
    schedule_time VARCHAR(8),
    schedule_weekday INT,
    timezone VARCHAR(64),
    retry_count INT,
    backoff_ms BIGINT,
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

CREATE TABLE IF NOT EXISTS job_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    -- V6__snapshot_mediumtext.sql：承载报文的三列提到 MEDIUMTEXT（#141，16 MB 上限）
    input_snapshot MEDIUMTEXT,
    error TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS message_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    step INT NOT NULL,
    action_name VARCHAR(255),
    status VARCHAR(16) NOT NULL,
    -- V6__snapshot_mediumtext.sql：承载报文的三列提到 MEDIUMTEXT（#141，16 MB 上限）
    data_snapshot MEDIUMTEXT,
    input_snapshot MEDIUMTEXT,
    error TEXT,
    created_at TIMESTAMP,
    attempt INT NOT NULL DEFAULT 1,
    duration_ms BIGINT,
    business_key VARCHAR(255),
    -- V5__message_step_batch.sql：批次身份三格（#139）——批号、批内序号、父消息标识
    batch_id VARCHAR(64),
    batch_seq INT,
    parent_message_id VARCHAR(64),
    CONSTRAINT uk_msg_step_run_msg_step UNIQUE (run_id, message_id, step)
);

CREATE INDEX IF NOT EXISTS idx_job_run_job ON job_run (job_id);
CREATE INDEX IF NOT EXISTS idx_msg_step_run ON message_step (run_id);
CREATE INDEX IF NOT EXISTS idx_msg_step_message ON message_step (message_id);
CREATE INDEX IF NOT EXISTS idx_msg_step_business ON message_step (business_key);
CREATE INDEX IF NOT EXISTS idx_msg_step_batch ON message_step (batch_id);

-- V3__mapping_sample.sql：编辑器里**粘进来的**那份样例落库（#119），一格一行
CREATE TABLE IF NOT EXISTS mapping_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    ref_id BIGINT NOT NULL,
    sample_json TEXT NOT NULL,
    updated_at DATETIME,
    CONSTRAINT uk_mapping_sample UNIQUE (job_id, kind, ref_id)
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
