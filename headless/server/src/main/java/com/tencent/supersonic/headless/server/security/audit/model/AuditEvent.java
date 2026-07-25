package com.tencent.supersonic.headless.server.security.audit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Getter;

import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * In-memory audit event. Raw question and SQL are transient inputs and must never be persisted or
 * logged. AuditEventService converts them to masked text and irreversible digests.
 */
@Getter
@Builder
public class AuditEvent {

    private String traceId;
    private Long chatId;
    private Long queryId;
    private String userName;
    private String organizationId;
    private AuditEventType eventType;
    private String resourceType;
    private String resourceId;
    @Builder.Default
    private AuditOutcome outcome = AuditOutcome.UNKNOWN;
    private String reasonCode;
    @JsonIgnore
    private String rawQuestion;
    @JsonIgnore
    private String rawSql;
    private Collection<String> metricCodes;
    private Collection<String> policyIds;
    private String maskingSummary;
    private Long exportRowCount;
    private String fileType;
    private Long fileSize;
    private Long durationMs;
    private Map<String, ?> metadata;
    private Date eventTime;
}
