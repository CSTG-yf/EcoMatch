-- BE-02 bank metric governance migration for PostgreSQL 12+
CREATE TABLE IF NOT EXISTS s2_metric_governance (
    id BIGSERIAL PRIMARY KEY,
    metric_id BIGINT NOT NULL UNIQUE,
    current_version INT NOT NULL,
    governance_status VARCHAR(32) NOT NULL,
    owner_department VARCHAR(255),
    source_system VARCHAR(255),
    business_definition TEXT,
    effective_from TIMESTAMP,
    effective_to TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS s2_metric_version (
    id BIGSERIAL PRIMARY KEY,
    metric_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    snapshot_json TEXT NOT NULL,
    change_summary VARCHAR(500),
    approval_status VARCHAR(32) NOT NULL,
    effective_from TIMESTAMP,
    effective_to TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    UNIQUE (metric_id, version_no)
);

CREATE TABLE IF NOT EXISTS s2_metric_approval (
    id BIGSERIAL PRIMARY KEY,
    metric_id BIGINT NOT NULL,
    version_id BIGINT,
    action VARCHAR(32) NOT NULL,
    approval_status VARCHAR(32) NOT NULL,
    comment_text VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    decided_at TIMESTAMP,
    decided_by VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS idx_metric_approval_metric
    ON s2_metric_approval (metric_id, created_at);

CREATE TABLE IF NOT EXISTS s2_metric_org_mapping (
    id BIGSERIAL PRIMARY KEY,
    metric_id BIGINT NOT NULL,
    organization_code VARCHAR(128) NOT NULL,
    external_metric_code VARCHAR(128) NOT NULL,
    external_metric_name VARCHAR(255) NOT NULL,
    business_definition TEXT,
    mapping_status VARCHAR(32) NOT NULL,
    effective_from TIMESTAMP,
    effective_to TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    UNIQUE (metric_id, organization_code, external_metric_code)
);
CREATE INDEX IF NOT EXISTS idx_metric_org_external
    ON s2_metric_org_mapping (organization_code, external_metric_code);
