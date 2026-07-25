package com.tencent.supersonic.headless.server.security.audit.model;

import lombok.Data;

@Data
public class SecurityAlertQuery {
    private long current = 1;
    private long pageSize = 20;
    private String ruleCode;
    private AlertSeverity severity;
    private AlertStatus status;
    private String userName;
    private String organizationId;
    private Long startTime;
    private Long endTime;
}
