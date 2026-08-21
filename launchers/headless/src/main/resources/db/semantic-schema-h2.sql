CREATE TABLE IF NOT EXISTS `s2_model` (
                                          `id` INT NOT NULL AUTO_INCREMENT  ,
                                          `name` varchar(255) DEFAULT NULL  , -- domain name
    `biz_name` varchar(255) DEFAULT NULL  , -- internal name
    `domain_id` INT DEFAULT '0'  , -- parent domain ID
    `tag_object_id` INT DEFAULT '0'  ,
    `alias` varchar(255) DEFAULT NULL  , -- internal name
    `status` INT DEFAULT NULL, -- 0 is off the shelf, 1 is normal
    `description` varchar(500) DEFAULT  NULL ,
    `created_at` TIMESTAMP DEFAULT NULL  ,
    `created_by` varchar(100) DEFAULT NULL  ,
    `updated_at` TIMESTAMP DEFAULT NULL  ,
    `updated_by` varchar(100) DEFAULT NULL  ,
    `admin` varchar(3000) DEFAULT NULL  , -- domain administrator
    `admin_org` varchar(3000) DEFAULT NULL  , -- domain administrators organization
    `is_open` TINYINT DEFAULT NULL  , -- whether the domain is public
    `viewer` varchar(3000) DEFAULT NULL  , -- domain available users
    `view_org` varchar(3000) DEFAULT NULL  , -- domain available organization
    `entity` varchar(500) DEFAULT NULL  , -- domain entity info
    `drill_down_dimensions` TEXT DEFAULT NULL  , -- drill down dimensions info
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_model IS 'model information';

CREATE TABLE IF NOT EXISTS `s2_domain` (
    `id` INT NOT NULL AUTO_INCREMENT  ,
    `name` varchar(255) DEFAULT NULL  , -- domain name
    `biz_name` varchar(255) DEFAULT NULL  , -- internal name
    `parent_id` INT DEFAULT '0'  , -- parent domain ID
    `status` INT NOT NULL  ,
    `created_at` TIMESTAMP DEFAULT NULL  ,
    `created_by` varchar(100) DEFAULT NULL  ,
    `updated_at` TIMESTAMP DEFAULT NULL  ,
    `updated_by` varchar(100) DEFAULT NULL  ,
    `is_unique` INT DEFAULT NULL  , -- 0 is non-unique, 1 is unique
    `admin` varchar(3000) DEFAULT NULL  , -- domain administrator
    `admin_org` varchar(3000) DEFAULT NULL  , -- domain administrators organization
    `is_open` TINYINT DEFAULT NULL  , -- whether the domain is public
    `viewer` varchar(3000) DEFAULT NULL  , -- domain available users
    `view_org` varchar(3000) DEFAULT NULL  , -- domain available organization
    `entity` varchar(500) DEFAULT NULL  , -- domain entity info
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_domain IS 'domain basic information';


CREATE TABLE `s2_database` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `name` varchar(255) NOT  NULL ,
    `description` varchar(500) DEFAULT  NULL ,
    `version` varchar(64) DEFAULT  NULL ,
    `type` varchar(20) NOT  NULL , -- type: mysql,clickhouse,tdw
    `config` varchar(655) NOT  NULL ,
    `created_at` TIMESTAMP NOT  NULL ,
    `created_by` varchar(100) NOT  NULL ,
    `updated_at` TIMESTAMP NOT  NULL ,
    `updated_by` varchar(100) NOT  NULL,
    `admin` varchar(500) NOT  NULL,
    `viewer` varchar(500) DEFAULT  NULL,
    PRIMARY KEY (`id`)
);
COMMENT ON TABLE s2_database IS 'database instance table';

CREATE TABLE  IF NOT EXISTS  `s2_datasource` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `model_id` INT NOT  NULL ,
    `name` varchar(255) NOT  NULL ,
    `biz_name` varchar(255) NOT  NULL ,
    `description` varchar(500) DEFAULT  NULL ,
    `database_id` INT NOT  NULL ,
    `datasource_detail` LONGVARCHAR NOT  NULL ,
    `status` int(11) DEFAULT NULL ,
    `created_at` TIMESTAMP NOT  NULL ,
    `created_by` varchar(100) NOT  NULL ,
    `updated_at` TIMESTAMP NOT  NULL ,
    `updated_by` varchar(100) NOT  NULL,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_datasource IS 'datasource table';

create table s2_user
(
    id       INT AUTO_INCREMENT,
    name     varchar(100) not null,
    display_name varchar(100) null,
    password varchar(100) null,
    email varchar(100) null,
    is_admin INT null,
    PRIMARY KEY (`id`)
);
COMMENT ON TABLE s2_user IS 'user information table';

create table s2_auth_groups
(
    group_id INT,
    model_id BIGINT,
    policy_code varchar(128),
    enabled SMALLINT DEFAULT 1,
    policy_version BIGINT DEFAULT 1,
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    updated_at TIMESTAMP,
    updated_by varchar(128),
    config LONGVARCHAR,
    PRIMARY KEY (`group_id`)
);
CREATE INDEX IF NOT EXISTS idx_auth_group_model_enabled
    ON s2_auth_groups(model_id, enabled);

CREATE TABLE IF NOT EXISTS `s2_metric` (
    `id` INT NOT NULL  AUTO_INCREMENT,
    `model_id` INT  NOT NULL ,
    `name` varchar(255)  NOT NULL ,
    `biz_name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `status` INT  NOT NULL , -- status, 0 is normal, 1 is off the shelf, 2 is deleted
    `sensitive_level` INT NOT NULL ,
    `type` varchar(50)  NOT NULL , -- type proxy,expr
    `type_params` LONGVARCHAR DEFAULT NULL  ,
    `created_at` TIMESTAMP NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` TIMESTAMP NOT NULL ,
    `updated_by` varchar(100) NOT NULL ,
    `data_format_type` varchar(50) DEFAULT NULL ,
    `data_format` varchar(500) DEFAULT NULL,
    `alias` varchar(500) DEFAULT NULL,
    `classifications` varchar(500) DEFAULT NULL,
    `relate_dimensions` varchar(500) DEFAULT NULL,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_metric IS 'metric information table';


CREATE TABLE IF NOT EXISTS `s2_dimension` (
    `id` INT NOT NULL  AUTO_INCREMENT ,
    `model_id` INT NOT NULL ,
    `datasource_id` INT  NOT NULL ,
    `name` varchar(255) NOT NULL ,
    `biz_name` varchar(255)  NOT NULL ,
    `description` varchar(500) NOT NULL ,
    `status` INT NOT NULL , -- status, 0 is normal, 1 is off the shelf, 2 is deleted
    `sensitive_level` INT DEFAULT NULL ,
    `type` varchar(50)  NOT NULL , -- type categorical,time
    `type_params` LONGVARCHAR  DEFAULT NULL ,
    `expr` LONGVARCHAR NOT NULL , -- expression
    `created_at` TIMESTAMP  NOT NULL ,
    `created_by` varchar(100)  NOT NULL ,
    `updated_at` TIMESTAMP  NOT NULL ,
    `updated_by` varchar(100)  NOT NULL ,
    `semantic_type` varchar(20)  NOT NULL,  -- semantic type: DATE, ID, CATEGORY
    `alias` varchar(500) DEFAULT NULL,
    `default_values` varchar(500) DEFAULT NULL,
    `dim_value_maps` varchar(5000) DEFAULT NULL,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_dimension IS 'dimension information table';

create table s2_datasource_rela
(
    id              INT AUTO_INCREMENT,
    model_id       INT       null,
    datasource_from INT       null,
    datasource_to   INT       null,
    join_key        varchar(100) null,
    created_at      TIMESTAMP     null,
    created_by      varchar(100) null,
    updated_at      TIMESTAMP     null,
    updated_by      varchar(100) null,
    PRIMARY KEY (`id`)
);
COMMENT ON TABLE s2_datasource_rela IS 'data source association table';

create table s2_view_info
(
    id         INT auto_increment,
    model_id  INT       null,
    type       varchar(20)  null comment 'datasource、dimension、metric',
    config     LONGVARCHAR   null comment 'config detail',
    created_at TIMESTAMP     null,
    created_by varchar(100) null,
    updated_at TIMESTAMP     null,
    updated_by varchar(100) not null
);
COMMENT ON TABLE s2_view_info IS 'view information table';


CREATE TABLE `s2_query_stat_info` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `trace_id` varchar(200) DEFAULT NULL, -- query unique identifier
    `model_id` INT DEFAULT NULL,
    `user`    varchar(200) DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ,
    `query_type` varchar(200) DEFAULT NULL, -- the corresponding scene
    `query_type_back` INT DEFAULT '0' , -- query type, 0-normal query, 1-pre-refresh type
    `query_sql_cmd`LONGVARCHAR , -- sql type request parameter
    `sql_cmd_md5` varchar(200) DEFAULT NULL, -- sql type request parameter md5
    `query_struct_cmd`LONGVARCHAR , -- struct type request parameter
    `struct_cmd_md5` varchar(200) DEFAULT NULL, -- struct type request parameter md5值
    `sql`LONGVARCHAR ,
    `sql_md5` varchar(200) DEFAULT NULL, -- sql md5
    `query_engine` varchar(20) DEFAULT NULL,
    `elapsed_ms` bigINT DEFAULT NULL,
    `query_state` varchar(20) DEFAULT NULL,
    `native_query` INT DEFAULT NULL, -- 1-detail query, 0-aggregation query
    `start_date` varchar(50) DEFAULT NULL,
    `end_date` varchar(50) DEFAULT NULL,
    `dimensions`LONGVARCHAR , -- dimensions involved in sql
    `metrics`LONGVARCHAR , -- metric  involved in sql
    `select_cols`LONGVARCHAR ,
    `agg_cols`LONGVARCHAR ,
    `filter_cols`LONGVARCHAR ,
    `group_by_cols`LONGVARCHAR ,
    `order_by_cols`LONGVARCHAR ,
    `use_result_cache` TINYINT DEFAULT '-1' , -- whether to hit the result cache
    `use_sql_cache` TINYINT DEFAULT '-1' , -- whether to hit the sql cache
    `sql_cache_key`LONGVARCHAR , -- sql cache key
    `result_cache_key`LONGVARCHAR , -- result cache key
    PRIMARY KEY (`id`)
) ;
COMMENT ON TABLE s2_query_stat_info IS 'query statistics table';

CREATE TABLE IF NOT EXISTS `s2_audit_event` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `event_id` VARCHAR(64) NOT NULL,
    `trace_id` VARCHAR(128) NOT NULL,
    `chat_id` BIGINT,
    `query_id` BIGINT,
    `user_name` VARCHAR(128) NOT NULL,
    `organization_id` VARCHAR(128),
    `event_type` VARCHAR(64) NOT NULL,
    `resource_type` VARCHAR(64),
    `resource_id` VARCHAR(255),
    `outcome` VARCHAR(32) NOT NULL,
    `reason_code` VARCHAR(64),
    `sanitized_question` LONGVARCHAR,
    `question_hash` VARCHAR(64),
    `metric_codes` LONGVARCHAR,
    `sql_type` VARCHAR(32),
    `sql_digest` VARCHAR(64),
    `policy_ids` LONGVARCHAR,
    `masking_summary` LONGVARCHAR,
    `export_row_count` BIGINT,
    `file_type` VARCHAR(64),
    `file_size` BIGINT,
    `client_ip_hash` VARCHAR(64),
    `user_agent_hash` VARCHAR(64),
    `duration_ms` BIGINT,
    `metadata_json` LONGVARCHAR,
    `event_time` TIMESTAMP NOT NULL,
    `previous_hash` VARCHAR(64),
    `event_hash` VARCHAR(64) NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (`event_id`),
    UNIQUE (`event_hash`),
    UNIQUE (`trace_id`, `previous_hash`)
);
CREATE INDEX IF NOT EXISTS `idx_audit_event_trace_time`
    ON `s2_audit_event` (`trace_id`, `event_time`);
CREATE INDEX IF NOT EXISTS `idx_audit_event_user_time`
    ON `s2_audit_event` (`user_name`, `event_time`);
CREATE INDEX IF NOT EXISTS `idx_audit_event_org_time`
    ON `s2_audit_event` (`organization_id`, `event_time`);
CREATE INDEX IF NOT EXISTS `idx_audit_event_anomaly_scope`
    ON `s2_audit_event` (`event_type`, `user_name`, `organization_id`, `event_time`);
CREATE INDEX IF NOT EXISTS `idx_audit_event_type_time`
    ON `s2_audit_event` (`event_type`, `event_time`);

CREATE TABLE IF NOT EXISTS `s2_audit_rule` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `rule_code` VARCHAR(64) NOT NULL,
    `rule_name` VARCHAR(128) NOT NULL,
    `rule_type` VARCHAR(64) NOT NULL,
    `threshold_value` BIGINT NOT NULL DEFAULT 1,
    `window_seconds` BIGINT NOT NULL DEFAULT 0,
    `work_hours_start` VARCHAR(8),
    `work_hours_end` VARCHAR(8),
    `severity` VARCHAR(16) NOT NULL,
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `config_json` LONGVARCHAR,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by` VARCHAR(128) NOT NULL,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by` VARCHAR(128) NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    UNIQUE (`rule_code`)
);
CREATE INDEX IF NOT EXISTS `idx_audit_rule_type_enabled`
    ON `s2_audit_rule` (`rule_type`, `enabled`);

CREATE TABLE IF NOT EXISTS `s2_security_alert` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `alert_id` VARCHAR(64) NOT NULL,
    `fingerprint` VARCHAR(64) NOT NULL,
    `rule_id` BIGINT NOT NULL,
    `rule_code` VARCHAR(64) NOT NULL,
    `trace_id` VARCHAR(128),
    `user_name` VARCHAR(128),
    `organization_id` VARCHAR(128),
    `resource_type` VARCHAR(64),
    `resource_id` VARCHAR(255),
    `severity` VARCHAR(16) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `description` LONGVARCHAR,
    `evidence_ids` LONGVARCHAR NOT NULL,
    `occurrence_count` BIGINT NOT NULL DEFAULT 1,
    `first_seen` TIMESTAMP NOT NULL,
    `last_seen` TIMESTAMP NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by` VARCHAR(128) NOT NULL,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by` VARCHAR(128) NOT NULL,
    UNIQUE (`alert_id`),
    UNIQUE (`fingerprint`)
);
CREATE INDEX IF NOT EXISTS `idx_security_alert_status_time`
    ON `s2_security_alert` (`status`, `last_seen`);
CREATE INDEX IF NOT EXISTS `idx_security_alert_rule_status`
    ON `s2_security_alert` (`rule_id`, `status`);
CREATE INDEX IF NOT EXISTS `idx_security_alert_severity_time`
    ON `s2_security_alert` (`severity`, `last_seen`);

CREATE TABLE IF NOT EXISTS `s2_alert_action` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `action_id` VARCHAR(64) NOT NULL,
    `alert_id` VARCHAR(64) NOT NULL,
    `from_status` VARCHAR(32),
    `to_status` VARCHAR(32) NOT NULL,
    `action` VARCHAR(32) NOT NULL,
    `operator_name` VARCHAR(128) NOT NULL,
    `comment` VARCHAR(2000),
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (`action_id`)
);
CREATE INDEX IF NOT EXISTS `idx_alert_action_alert_time`
    ON `s2_alert_action` (`alert_id`, `created_at`);


CREATE TABLE IF NOT EXISTS `s2_semantic_pasre_info` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `trace_id` varchar(200) NOT NULL  ,
    `model_id` INT  NOT NULL ,
    `dimensions`LONGVARCHAR ,
    `metrics`LONGVARCHAR ,
    `orders`LONGVARCHAR ,
    `filters`LONGVARCHAR ,
    `date_info`LONGVARCHAR ,
    `limit` INT NOT NULL ,
    `native_query` TINYINT NOT NULL DEFAULT '0' ,
    `sql`LONGVARCHAR ,
    `created_at` TIMESTAMP  NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `status` INT NOT NULL ,
    `elapsed_ms` bigINT DEFAULT NULL ,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_semantic_pasre_info IS 'semantic layer sql parsing information table';


CREATE TABLE IF NOT EXISTS `s2_available_date_info` (
    `id` INT NOT NULL  AUTO_INCREMENT ,
    `item_id` INT NOT NULL ,
    `type`    varchar(255) NOT NULL ,
    `date_format` varchar(64)  NOT NULL ,
    `start_date`  varchar(64) ,
    `end_date`  varchar(64) ,
    `unavailable_date` LONGVARCHAR  DEFAULT NULL ,
    `created_at` TIMESTAMP  NOT NULL ,
    `created_by` varchar(100)  NOT NULL ,
    `updated_at` TIMESTAMP  NOT NULL ,
    `updated_by` varchar(100)  NOT NULL ,
    `date_period` varchar(100)  DEFAULT NULL ,
    `status` INT  DEFAULT '0', -- 1-in use  0 is normal, 1 is off the shelf, 2 is deleted
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_available_date_info IS 's2_available_date_info information table';


-------demo for semantic and chat
CREATE TABLE IF NOT EXISTS `s2_user_department` (
    `user_name` varchar(200) NOT NULL,
    `department` varchar(200) NOT NULL -- department of user
    );
COMMENT ON TABLE s2_semantic_pasre_info IS 'user_department_info';

CREATE TABLE IF NOT EXISTS `s2_pv_uv_statis` (
    `imp_date` varchar(200) NOT NULL,
    `user_name` varchar(200) NOT NULL,
    `page` varchar(200) NOT NULL
    );
COMMENT ON TABLE s2_semantic_pasre_info IS 'user_access_info';

CREATE TABLE IF NOT EXISTS `s2_stay_time_statis` (
    `imp_date` varchar(200) NOT NULL,
    `user_name` varchar(200) NOT NULL,
    `stay_hours` DOUBLE NOT NULL,
    `page` varchar(200) NOT NULL
    );
COMMENT ON TABLE s2_stay_time_statis IS 's2_stay_time_statis_info';

CREATE TABLE IF NOT EXISTS `singer` (
    `imp_date` varchar(200) NOT NULL,
    `singer_name` varchar(200) NOT NULL,
    `act_area` varchar(200) NOT NULL,
    `song_name` varchar(200) NOT NULL,
    `genre` varchar(200) NOT NULL,
    `js_play_cnt` bigINT DEFAULT NULL,
    `down_cnt` bigINT DEFAULT NULL,
    `favor_cnt` bigINT DEFAULT NULL
    );
COMMENT ON TABLE singer IS 'singer_info';

CREATE TABLE IF NOT EXISTS `s2_tag_object` (
    `id` INT NOT NULL  AUTO_INCREMENT,
    `domain_id` INT  NOT NULL ,
    `name` varchar(255)  NOT NULL ,
    `biz_name` varchar(255)  NOT NULL ,
    `description` varchar(500) DEFAULT NULL ,
    `status` INT  NOT NULL ,
    `sensitive_level` INT NOT NULL ,
    `created_at` TIMESTAMP NOT NULL ,
    `created_by` varchar(100) NOT NULL ,
    `updated_at` TIMESTAMP DEFAULT NULL ,
    `updated_by` varchar(100) DEFAULT NULL ,
    `ext` LONGVARCHAR DEFAULT NULL  ,
    PRIMARY KEY (`id`)
    );
COMMENT ON TABLE s2_tag IS 'tag object information';


CREATE TABLE IF NOT EXISTS `s2_metric_governance` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `metric_id` BIGINT NOT NULL,
    `current_version` INT NOT NULL,
    `governance_status` VARCHAR(32) NOT NULL,
    `owner_department` VARCHAR(255),
    `source_system` VARCHAR(255),
    `business_definition` LONGVARCHAR,
    `effective_from` TIMESTAMP,
    `effective_to` TIMESTAMP,
    `created_at` TIMESTAMP NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `updated_at` TIMESTAMP NOT NULL,
    `updated_by` VARCHAR(100) NOT NULL,
    UNIQUE (`metric_id`)
);

CREATE TABLE IF NOT EXISTS `s2_metric_version` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `metric_id` BIGINT NOT NULL,
    `version_no` INT NOT NULL,
    `snapshot_json` LONGVARCHAR NOT NULL,
    `change_summary` VARCHAR(500),
    `approval_status` VARCHAR(32) NOT NULL,
    `effective_from` TIMESTAMP,
    `effective_to` TIMESTAMP,
    `created_at` TIMESTAMP NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    UNIQUE (`metric_id`, `version_no`)
);

CREATE TABLE IF NOT EXISTS `s2_metric_approval` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `metric_id` BIGINT NOT NULL,
    `version_id` BIGINT,
    `action` VARCHAR(32) NOT NULL,
    `approval_status` VARCHAR(32) NOT NULL,
    `comment_text` VARCHAR(1000),
    `created_at` TIMESTAMP NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `decided_at` TIMESTAMP,
    `decided_by` VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS `idx_metric_approval_metric`
    ON `s2_metric_approval` (`metric_id`, `created_at`);

CREATE TABLE IF NOT EXISTS `s2_metric_org_mapping` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `metric_id` BIGINT NOT NULL,
    `organization_code` VARCHAR(128) NOT NULL,
    `external_metric_code` VARCHAR(128) NOT NULL,
    `external_metric_name` VARCHAR(255) NOT NULL,
    `business_definition` LONGVARCHAR,
    `mapping_status` VARCHAR(32) NOT NULL,
    `effective_from` TIMESTAMP,
    `effective_to` TIMESTAMP,
    `created_at` TIMESTAMP NOT NULL,
    `created_by` VARCHAR(100) NOT NULL,
    `updated_at` TIMESTAMP NOT NULL,
    `updated_by` VARCHAR(100) NOT NULL,
    UNIQUE (`metric_id`, `organization_code`, `external_metric_code`)
);
CREATE INDEX IF NOT EXISTS `idx_metric_org_external`
    ON `s2_metric_org_mapping` (`organization_code`, `external_metric_code`);


