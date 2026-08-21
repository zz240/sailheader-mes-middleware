CREATE TABLE IF NOT EXISTS po_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    vendor_code VARCHAR(32),
    order_date DATETIME,
    status VARCHAR(16),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO po_order (order_no, vendor_code, order_date, status) VALUES
    ('PO-2026-001', 'V001', '2026-08-14 10:00:00', 'ACTIVE'),
    ('PO-2026-002', 'V002', '2026-08-14 11:00:00', 'ACTIVE');

CREATE TABLE IF NOT EXISTS po_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    material VARCHAR(64),
    qty INT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS watermark (
    job_key VARCHAR(64) PRIMARY KEY,
    wm_value BIGINT NOT NULL DEFAULT 0
);

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
    addition JSON,
    CONSTRAINT uk_data_source_site_name UNIQUE (site_id, name)
);

CREATE TABLE IF NOT EXISTS job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    status INT NOT NULL,
    direction VARCHAR(16),
    crontab VARCHAR(128),
    timezone VARCHAR(64),
    retry_count INT,
    backoff_ms BIGINT,
    watermark_type VARCHAR(20),
    watermark_column VARCHAR(100),
    remarks TEXT,
    last_run_status VARCHAR(16),
    last_run_at DATETIME,
    last_error TEXT,
    CONSTRAINT uk_job_site_name UNIQUE (site_id, name)
);

CREATE TABLE IF NOT EXISTS job_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    action_index INT NOT NULL,
    addition JSON,
    CONSTRAINT uk_action_job_index UNIQUE (job_id, action_index)
);

CREATE TABLE IF NOT EXISTS template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(64) NOT NULL,
    config_json JSON,
    CONSTRAINT uk_template_name_version UNIQUE (name, version)
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
