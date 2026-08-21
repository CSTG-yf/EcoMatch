package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditIntegrityMonitorTest {

    private final AuditEventMapper eventMapper = mock(AuditEventMapper.class);
    private final AuditEventMutationService mutationService = mock(AuditEventMutationService.class);
    private final AuditRuleService ruleService = mock(AuditRuleService.class);
    private final SecurityAlertService alertService = mock(SecurityAlertService.class);
    private final AuditIntegrityMonitor monitor = new AuditIntegrityMonitor(eventMapper,
            mutationService, ruleService, alertService,
            new AuditProperties(true, "Asia/Shanghai", 100, 500));

    @Test
    void turnsInvalidAuditHashIntoAlertEvidence() {
        AuditRuleDO rule = new AuditRuleDO();
        rule.setRuleCode(AlertRuleType.AUDIT_INTEGRITY_FAILURE.name());
        rule.setRuleType(AlertRuleType.AUDIT_INTEGRITY_FAILURE.name());
        AuditEventDO event = new AuditEventDO();
        event.setId(42L);
        event.setEventId("event-42");
        event.setOrganizationId("branch-1");
        when(ruleService.listEnabled()).thenReturn(List.of(rule));
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(mutationService.hasValidHash(event)).thenReturn(false);

        monitor.scan();

        verify(alertService).upsert(eq(rule), any(AuditEventDO.class), any(),
                eq("Audit hash-chain integrity failure"), any(), eq(1L));
    }
}
