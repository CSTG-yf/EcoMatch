package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.EnumSet;

@Slf4j
@Service
public class AuditAnomalyEngine {

    private static final EnumSet<AuditEventType> OFF_HOURS_EVENTS =
            EnumSet.of(AuditEventType.QUERY_STARTED, AuditEventType.EXPORT_STARTED,
                    AuditEventType.SHARE_ACCESSED, AuditEventType.OBJECT_ACCESS_ALLOWED);

    private final AuditRuleService auditRuleService;
    private final AuditEventMapper auditEventMapper;
    private final SecurityAlertService securityAlertService;
    private final AuditProperties properties;
    private final ObjectMapper objectMapper;

    public AuditAnomalyEngine(AuditRuleService auditRuleService, AuditEventMapper auditEventMapper,
            SecurityAlertService securityAlertService, AuditProperties properties,
            ObjectMapper objectMapper) {
        this.auditRuleService = auditRuleService;
        this.auditEventMapper = auditEventMapper;
        this.securityAlertService = securityAlertService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void evaluate(AuditEventDO event) {
        for (AuditRuleDO rule : auditRuleService.listEnabled()) {
            try {
                evaluate(rule, event);
            } catch (RuntimeException e) {
                log.error("Audit rule evaluation failed: rule={}, event={}, errorType={}",
                        SensitiveLogUtils.summarize(rule.getRuleCode()),
                        SensitiveLogUtils.summarize(event.getEventId()),
                        e.getClass().getSimpleName());
            }
        }
    }

    private void evaluate(AuditRuleDO rule, AuditEventDO event) {
        AlertRuleType type = AlertRuleType.valueOf(rule.getRuleType());
        switch (type) {
            case HIGH_FREQUENCY_QUERY -> evaluateFrequency(rule, event,
                    AuditEventType.QUERY_STARTED, "High-frequency query detected", false);
            case REPEATED_AUTH_DENIAL -> evaluateFrequency(rule, event, AuditEventType.AUTH_DENIED,
                    "Repeated authorization denial detected", false);
            case BULK_EXPORT -> evaluateBulkExport(rule, event);
            case OFF_HOURS_ACCESS -> evaluateOffHours(rule, event);
            case SENSITIVE_RESOURCE_ACCESS -> evaluateFrequency(rule, event,
                    AuditEventType.MASK_APPLIED, "Frequent sensitive-resource access detected",
                    true);
        }
    }

    private void evaluateFrequency(AuditRuleDO rule, AuditEventDO event,
            AuditEventType expectedType, String title, boolean scopeResource) {
        if (!expectedType.name().equals(event.getEventType())) {
            return;
        }
        long count = countEvents(event, expectedType, rule.getWindowSeconds(), scopeResource);
        if (count < rule.getThresholdValue()) {
            return;
        }
        createOrUpdateAlert(rule, event, title,
                "Observed " + count + " matching events in " + rule.getWindowSeconds() + " seconds",
                count);
    }

    private void evaluateBulkExport(AuditRuleDO rule, AuditEventDO event) {
        boolean exportStarted = AuditEventType.EXPORT_STARTED.name().equals(event.getEventType());
        boolean exportSucceeded =
                AuditEventType.EXPORT_SUCCEEDED.name().equals(event.getEventType());
        if (!exportStarted && !exportSucceeded) {
            return;
        }
        long exportCount =
                countEvents(event, AuditEventType.EXPORT_STARTED, rule.getWindowSeconds(), false);
        long rowThreshold = configurationLong(rule.getConfigJson(), "rowThreshold", 10000L);
        boolean largeExport = exportSucceeded && event.getExportRowCount() != null
                && event.getExportRowCount() >= rowThreshold;
        if (!largeExport && exportCount < rule.getThresholdValue()) {
            return;
        }
        String description = largeExport
                ? "Exported row count reached configured threshold " + rowThreshold
                : "Observed " + exportCount + " exports in " + rule.getWindowSeconds() + " seconds";
        createOrUpdateAlert(rule, event, "Bulk export behavior detected", description,
                Math.max(1, exportCount));
    }

    private void evaluateOffHours(AuditRuleDO rule, AuditEventDO event) {
        AuditEventType eventType;
        try {
            eventType = AuditEventType.valueOf(event.getEventType());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!OFF_HOURS_EVENTS.contains(eventType)) {
            return;
        }
        ZonedDateTime eventTime =
                Instant.ofEpochMilli(event.getEventTime().getTime()).atZone(properties.getZoneId());
        LocalTime start = LocalTime.parse(rule.getWorkHoursStart());
        LocalTime end = LocalTime.parse(rule.getWorkHoursEnd());
        if (isWithinAllowedHours(eventTime.toLocalTime(), start, end)) {
            return;
        }
        createOrUpdateAlert(rule, event, "Off-hours access detected",
                "Access occurred outside configured work hours", 1);
    }

    private boolean isWithinAllowedHours(LocalTime value, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !value.isBefore(start) && value.isBefore(end);
        }
        return !value.isBefore(start) || value.isBefore(end);
    }

    private long countEvents(AuditEventDO event, AuditEventType eventType, Long windowSeconds,
            boolean scopeResource) {
        long window = Math.max(1, windowSeconds == null ? 1 : windowSeconds);
        Date end = event.getEventTime() == null ? new Date() : event.getEventTime();
        Date start = new Date(end.getTime() - window * 1000L);
        LambdaQueryWrapper<AuditEventDO> wrapper = new LambdaQueryWrapper<AuditEventDO>()
                .eq(AuditEventDO::getEventType, eventType.name())
                .eq(AuditEventDO::getUserName, event.getUserName())
                .ge(AuditEventDO::getEventTime, start).le(AuditEventDO::getEventTime, end);
        if (StringUtils.isNotBlank(event.getOrganizationId())) {
            wrapper.eq(AuditEventDO::getOrganizationId, event.getOrganizationId());
        }
        if (scopeResource && StringUtils.isNotBlank(event.getResourceType())) {
            wrapper.eq(AuditEventDO::getResourceType, event.getResourceType());
        }
        if (scopeResource && StringUtils.isNotBlank(event.getResourceId())) {
            wrapper.eq(AuditEventDO::getResourceId, event.getResourceId());
        }
        return auditEventMapper.selectCount(wrapper);
    }

    private void createOrUpdateAlert(AuditRuleDO rule, AuditEventDO event, String title,
            String description, long occurrenceCount) {
        String fingerprint = fingerprint(rule, event);
        securityAlertService.upsert(rule, event, fingerprint, title, description, occurrenceCount);
    }

    private String fingerprint(AuditRuleDO rule, AuditEventDO event) {
        long window = Math.max(1, rule.getWindowSeconds() == null ? 1 : rule.getWindowSeconds());
        long eventMillis = event.getEventTime() == null ? System.currentTimeMillis()
                : event.getEventTime().getTime();
        String bucket;
        if (AlertRuleType.OFF_HOURS_ACCESS.name().equals(rule.getRuleType())) {
            bucket = LocalDate.ofInstant(Instant.ofEpochMilli(eventMillis), properties.getZoneId())
                    .toString();
        } else {
            bucket = String.valueOf(eventMillis / (window * 1000L));
        }
        String resource = AlertRuleType.SENSITIVE_RESOURCE_ACCESS.name().equals(rule.getRuleType())
                ? StringUtils.defaultString(event.getResourceId(), "*")
                : "*";
        String source = String.join("|", rule.getRuleCode(),
                StringUtils.defaultString(event.getUserName(), "anonymous"),
                StringUtils.defaultString(event.getOrganizationId(), "-"), resource, bucket);
        return DigestUtils.sha256Hex(source);
    }

    private long configurationLong(String json, String field, long defaultValue) {
        if (StringUtils.isBlank(json)) {
            return defaultValue;
        }
        try {
            JsonNode node = objectMapper.readTree(json).get(field);
            return node == null || !node.canConvertToLong() ? defaultValue : node.asLong();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
