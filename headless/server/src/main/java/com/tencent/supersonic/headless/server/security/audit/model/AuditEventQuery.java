package com.tencent.supersonic.headless.server.security.audit.model;

import lombok.Data;

@Data
public class AuditEventQuery {
    private long current = 1;
    private long pageSize = 20;
    private String traceId;
    private String userName;
    private String organizationId;
    private AuditEventType eventType;
    private AuditOutcome outcome;
    private String resourceType;
    private String resourceId;
    private Long startTime;
    private Long endTime;
}
