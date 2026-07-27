package com.tencent.supersonic.headless.server.security.audit.model;

import lombok.Data;

@Data
public class AuditRuleRequest {
    private String ruleCode;
    private String ruleName;
    private AlertRuleType ruleType;
    private Long thresholdValue;
    private Long windowSeconds;
    private String workHoursStart;
    private String workHoursEnd;
    private AlertSeverity severity;
    private Boolean enabled;
    private String configJson;
    private Integer version;
}
