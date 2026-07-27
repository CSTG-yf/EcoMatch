-- chat tables
CREATE TABLE IF NOT EXISTS `s2_chat_context`
(
    `chat_id`        BIGINT NOT NULL , -- context chat id
    `modified_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP , -- row modify time
    `user`           varchar(64) DEFAULT NULL , -- row modify user
    `query_text`     LONGVARCHAR DEFAULT NULL , -- query text
    `semantic_parse` LONGVARCHAR DEFAULT NULL , -- parse data
    `ext_data`       LONGVARCHAR DEFAULT NULL , -- extend data
    PRIMARY KEY (`chat_id`)
    );

CREATE TABLE IF NOT EXISTS `s2_chat`
(
    `chat_id`       BIGINT auto_increment ,-- AUTO_INCREMENT,
    `agent_id`       INT DEFAULT NULL,
    `chat_name`     varchar(100) DEFAULT NULL,
    `create_time`   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
    `last_time`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP ,
    `creator`       varchar(30)  DEFAULT NULL,
    `last_question` varchar(200) DEFAULT NULL,
    `is_delete`     INT DEFAULT '0' COMMENT 'is deleted',
    `is_top`        INT DEFAULT '0' COMMENT 'is top',
    PRIMARY KEY (`chat_id`)
    ) ;


CREATE TABLE `s2_chat_query`
(
    `question_id`             BIGINT  NOT NULL AUTO_INCREMENT,
    `agent_id`             INT  NULL,
    `create_time`       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `query_text`          mediumtext,
    `user_name`         varchar(150)  DEFAULT NULL COMMENT '',
    `query_state`             int(1) DEFAULT NULL,
    `chat_id`           BIGINT NOT NULL , -- context chat id
    `query_result` mediumtext NOT NULL ,
    `score`             int DEFAULT '0',
    `feedback`          varchar(1024) DEFAULT '',
    PRIMARY KEY (`question_id`)
);

CREATE TABLE `s2_chat_parse`
(
    `question_id`             BIGINT  NOT NULL,
    `chat_id`           BIGINT NOT NULL ,
    `parse_id`          INT NOT NULL ,
    `create_time`       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `query_text`          varchar(500),
    `user_name`         varchar(150)  DEFAULT NULL COMMENT '',
    `parse_info` mediumtext NOT NULL ,
    `is_candidate` INT DEFAULT 1 COMMENT '1是candidate,0是selected'
);

CREATE TABLE `s2_chat_statistics`
(
    `question_id`             BIGINT  NOT NULL,
    `chat_id`           BIGINT NOT NULL ,
    `user_name`         varchar(150)  DEFAULT NULL COMMENT '',
    `query_text`          varchar(200),
    `interface_name`         varchar(100)  DEFAULT NULL COMMENT '',
    `cost` INT(6) NOT NULL ,
    `type` INT NOT NULL ,
    `create_time`       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `s2_chat_config` (
                                                `id` INT NOT NULL AUTO_INCREMENT,
                                                `model_id` INT DEFAULT NULL ,
                                                `chat_detail_config` varchar(655) ,
    `chat_agg_config` varchar(655)    ,
    `recommended_questions`  varchar(1500)    ,
    `created_at` TIMESTAMP  NOT NULL   ,
    `updated_at` TIMESTAMP  NOT NULL   ,
    `created_by` varchar(100) NOT NULL   ,
    `updated_by` varchar(100) NOT NULL   ,
    `status` INT NOT NULL  DEFAULT '0' , -- domain extension information status : 0 is normal, 1 is off the shelf, 2 is deleted
    PRIMARY KEY (`id`)
    ) ;
COMMENT ON TABLE s2_chat_config IS 'chat config information table ';


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


CREATE TABLE IF NOT EXISTS `s2_plugin`
(
    `id`         INT AUTO_INCREMENT,
    `type`      varchar(50)   NULL,
    `model`     varchar(100)  NULL,
    `pattern`    varchar(500)  NULL,
    `parse_mode` varchar(100)  NULL,
    `parse_mode_config` LONGVARCHAR  NULL,
    `name`       varchar(100)  NULL,
    `created_at` TIMESTAMP   NULL,
    `created_by` varchar(100) null,
    `updated_at` TIMESTAMP    NULL,
    `updated_by` varchar(100) NULL,
    `config`     LONGVARCHAR  NULL,
    `comment`     LONGVARCHAR  NULL,
    PRIMARY KEY (`id`)
    ); COMMENT ON TABLE s2_plugin IS 'plugin information table';

CREATE TABLE IF NOT EXISTS s2_agent
(
    id          int AUTO_INCREMENT,
    name        varchar(100)  null,
    description varchar(500) null,
    status       int null,
    examples    varchar(500) null,
    config      varchar(2000)  null,
    created_by  varchar(100) null,
    created_at  TIMESTAMP  null,
    updated_by  varchar(100) null,
    updated_at  TIMESTAMP null,
    enable_search int null,
    PRIMARY KEY (`id`)
    ); COMMENT ON TABLE s2_agent IS 'agent information table';


-------demo for semantic and chat
CREATE TABLE IF NOT EXISTS `s2_user_department` (
    `user_name` varchar(200) NOT NULL,
    `department` varchar(200) NOT NULL -- department of user
    );
COMMENT ON TABLE s2_user_department IS 'user_department_info';

--
-- CREATE TABLE IF NOT EXISTS `s2_dictionary_task` (
--                                                     `id` INT NOT NULL AUTO_INCREMENT,
--                                                     `name` varchar(255) NOT NULL , -- task name
--     `description` varchar(255) ,
--     `command`LONGVARCHAR  NOT NULL , -- task Request Parameters
--     `command_md5` varchar(255)  NOT NULL , -- task Request Parameters md5
--     `status` INT NOT NULL , -- the final status of the task
--     `dimension_ids` varchar(500)  NULL ,
--     `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP  ,
--     `created_by` varchar(100) NOT NULL ,
--     `progress` DOUBLE default 0.00  ,  -- task real-time progress
--     `elapsed_ms` bigINT DEFAULT NULL , -- the task takes time in milliseconds
--     `message` LONGVARCHAR  , -- remark related information
--     PRIMARY KEY (`id`)
--     );
-- COMMENT ON TABLE s2_dictionary_task IS 'dictionary task information table';

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




