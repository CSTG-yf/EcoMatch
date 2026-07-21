-- BE-02 bank metric governance migration for MySQL 8+
CREATE TABLE IF NOT EXISTS `s2_metric_governance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `metric_id` BIGINT NOT NULL,
    `current_version` INT NOT NULL,
    `governance_status` VARCHAR(32) NOT NULL,
    `owner_department` VARCHAR(255),
    `source_system` VARCHAR(255),
    `business_definition` TEXT,
    `effective_from` DATETIME,
    `effective_to` DATETIME,
    `created_at` DATETIME NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `updated_at` DATETIME NOT NULL,
    `updated_by` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metric_governance_metric` (`metric_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_metric_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `metric_id` BIGINT NOT NULL,
    `version_no` INT NOT NULL,
    `snapshot_json` LONGTEXT NOT NULL,
    `change_summary` VARCHAR(500),
    `approval_status` VARCHAR(32) NOT NULL,
    `effective_from` DATETIME,
    `effective_to` DATETIME,
    `created_at` DATETIME NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metric_version` (`metric_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_metric_approval` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `metric_id` BIGINT NOT NULL,
    `version_id` BIGINT,
    `action` VARCHAR(32) NOT NULL,
    `approval_status` VARCHAR(32) NOT NULL,
    `comment_text` VARCHAR(1000),
    `created_at` DATETIME NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `decided_at` DATETIME,
    `decided_by` VARCHAR(100),
    PRIMARY KEY (`id`),
    KEY `idx_metric_approval_metric` (`metric_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `s2_metric_org_mapping` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `metric_id` BIGINT NOT NULL,
    `organization_code` VARCHAR(128) NOT NULL,
    `external_metric_code` VARCHAR(128) NOT NULL,
    `external_metric_name` VARCHAR(255) NOT NULL,
    `business_definition` TEXT,
    `mapping_status` VARCHAR(32) NOT NULL,
    `effective_from` DATETIME,
    `effective_to` DATETIME,
    `created_at` DATETIME NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `updated_at` DATETIME NOT NULL,
    `updated_by` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metric_org_mapping`
        (`metric_id`, `organization_code`, `external_metric_code`),
    KEY `idx_metric_org_external` (`organization_code`, `external_metric_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
