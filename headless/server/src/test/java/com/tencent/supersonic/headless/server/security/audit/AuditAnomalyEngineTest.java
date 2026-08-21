package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditAnomalyEngineTest {

    private final AuditRuleService ruleService = mock(AuditRuleService.class);
    private final AuditEventMapper eventMapper = mock(AuditEventMapper.class);
    private final SecurityAlertService alertService = mock(SecurityAlertService.class);
    private final AuditProperties properties = new AuditProperties(true, "Asia/Shanghai", 100, 500);
    private final AuditAnomalyEngine engine = new AuditAnomalyEngine(ruleService, eventMapper,
            alertService, properties, new ObjectMapper());

    @Test
    void createsDeduplicatedAlertWhenFrequencyThresholdIsReached() {
        AuditRuleDO rule = rule(AlertRuleType.HIGH_FREQUENCY_QUERY, 3, 60);
        AuditEventDO event = event(AuditEventType.QUERY_STARTED, new Date());
        when(ruleService.listEnabled()).thenReturn(List.of(rule));
        when(eventMapper.selectCount(any())).thenReturn(3L);

        engine.evaluate(event);

        verify(alertService).upsert(eq(rule), eq(event), any(), eq("High-frequency query detected"),
                any(), eq(3L));
    }

    @Test
    void doesNotAlertBelowFrequencyThreshold() {
        AuditRuleDO rule = rule(AlertRuleType.REPEATED_AUTH_DENIAL, 3, 300);
        AuditEventDO event = event(AuditEventType.AUTH_DENIED, new Date());
        when(ruleService.listEnabled()).thenReturn(List.of(rule));
        when(eventMapper.selectCount(any())).thenReturn(2L);

        engine.evaluate(event);

        verify(alertService, never()).upsert(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void evaluatesOffHoursInConfiguredBusinessTimeZone() {
        AuditRuleDO rule = rule(AlertRuleType.OFF_HOURS_ACCESS, 1, 0);
        rule.setWorkHoursStart("07:00");
        rule.setWorkHoursEnd("22:00");
        Date atNight = Date.from(LocalDateTime.of(2026, 7, 25, 23, 0)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        AuditEventDO event = event(AuditEventType.QUERY_STARTED, atNight);
        when(ruleService.listEnabled()).thenReturn(List.of(rule));

        engine.evaluate(event);

        verify(alertService).upsert(eq(rule), eq(event), any(), eq("Off-hours access detected"),
                any(), eq(1L));
    }

    @Test
    void alertsWhenOneUserTouchesMultipleOrganizations() {
        AuditRuleDO rule = rule(AlertRuleType.CROSS_ORGANIZATION_ACCESS, 2, 300);
        AuditEventDO event = event(AuditEventType.QUERY_STARTED, new Date());
        when(ruleService.listEnabled()).thenReturn(List.of(rule));
        when(eventMapper.selectObjs(any())).thenReturn(List.of(2L));

        engine.evaluate(event);

        verify(alertService).upsert(eq(rule), eq(event), any(),
                eq("Cross-organization access detected"), any(), eq(2L));
    }

    @Test
    void alertsWhenPolicyChangesSpike() {
        AuditRuleDO rule = rule(AlertRuleType.POLICY_CHANGE_SPIKE, 2, 300);
        AuditEventDO event = event(AuditEventType.POLICY_UPDATED, new Date());
        when(ruleService.listEnabled()).thenReturn(List.of(rule));
        when(eventMapper.selectCount(any())).thenReturn(2L);

        engine.evaluate(event);

        verify(alertService).upsert(eq(rule), eq(event), any(),
                eq("Authorization policy change spike"), any(), eq(2L));
    }

    private AuditRuleDO rule(AlertRuleType type, long threshold, long window) {
        AuditRuleDO rule = new AuditRuleDO();
        rule.setId(1L);
        rule.setRuleCode(type.name());
        rule.setRuleName(type.name());
        rule.setRuleType(type.name());
        rule.setThresholdValue(threshold);
        rule.setWindowSeconds(window);
        rule.setSeverity("HIGH");
        rule.setEnabled(true);
        return rule;
    }

    private AuditEventDO event(AuditEventType type, Date time) {
        AuditEventDO event = new AuditEventDO();
        event.setEventId("event-1");
        event.setTraceId("trace-1");
        event.setUserName("analyst");
        event.setOrganizationId("branch-1");
        event.setEventType(type.name());
        event.setEventTime(time);
        return event;
    }
}
