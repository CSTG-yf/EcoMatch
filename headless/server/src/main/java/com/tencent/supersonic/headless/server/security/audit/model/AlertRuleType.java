package com.tencent.supersonic.headless.server.security.audit.model;

public enum AlertRuleType {
    HIGH_FREQUENCY_QUERY,
    BULK_EXPORT,
    REPEATED_AUTH_DENIAL,
    OFF_HOURS_ACCESS,
    SENSITIVE_RESOURCE_ACCESS,
    CROSS_ORGANIZATION_ACCESS,
    POLICY_CHANGE_SPIKE,
    AUDIT_INTEGRITY_FAILURE
}
