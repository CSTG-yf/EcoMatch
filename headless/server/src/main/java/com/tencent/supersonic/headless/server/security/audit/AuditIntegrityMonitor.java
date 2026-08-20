package com.tencent.supersonic.headless.server.security.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/** Periodically checks recent audit hashes and turns tampering into a high-severity alert. */
@Slf4j
@Component
public class AuditIntegrityMonitor {

    private static final int MAX_EVENTS_PER_SCAN = 1_000;

    private final AuditEventMapper auditEventMapper;
    private final AuditEventMutationService mutationService;
    private final AuditRuleService auditRuleService;
    private final SecurityAlertService securityAlertService;
    private final AuditProperties properties;

    public AuditIntegrityMonitor(AuditEventMapper auditEventMapper,
            AuditEventMutationService mutationService, AuditRuleService auditRuleService,
            SecurityAlertService securityAlertService, AuditProperties properties) {
        this.auditEventMapper = auditEventMapper;
        this.mutationService = mutationService;
        this.auditRuleService = auditRuleService;
        this.securityAlertService = securityAlertService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${s2.security.audit.integrity-check-ms:60000}")
    public void scan() {
        if (!properties.isEnabled()) {
            return;
        }
        List<AuditRuleDO> rules = auditRuleService.listEnabled().stream()
                .filter(rule -> AlertRuleType.AUDIT_INTEGRITY_FAILURE.name()
                        .equals(rule.getRuleType())).toList();
        if (rules.isEmpty()) {
            return;
        }
        List<AuditEventDO> events = auditEventMapper.selectList(new QueryWrapper<AuditEventDO>()
                .orderByDesc("id").last("LIMIT " + MAX_EVENTS_PER_SCAN));
        if (events == null) {
            return;
        }
        for (AuditEventDO event : events) {
            if (mutationService.hasValidHash(event)) {
                continue;
            }
            for (AuditRuleDO rule : rules) {
                raiseAlert(rule, event);
            }
        }
    }

    private void raiseAlert(AuditRuleDO rule, AuditEventDO invalidEvent) {
        Date now = new Date();
        AuditEventDO evidence = new AuditEventDO();
        evidence.setEventId("integrity-" + (invalidEvent.getEventId() == null
                ? DigestUtils.sha256Hex(String.valueOf(invalidEvent.getId()))
                : invalidEvent.getEventId()));
        evidence.setTraceId("audit-integrity-monitor");
        evidence.setUserName("system");
        evidence.setOrganizationId(invalidEvent.getOrganizationId());
        evidence.setEventType(AuditEventType.AUDIT_INTEGRITY_FAILURE.name());
        evidence.setOutcome("DENIED");
        evidence.setReasonCode("AUDIT_HASH_MISMATCH");
        evidence.setResourceType("AUDIT_EVENT");
        evidence.setResourceId(invalidEvent.getEventId());
        evidence.setEventTime(now);
        String source = rule.getRuleCode() + "|" + evidence.getResourceId();
        securityAlertService.upsert(rule, evidence, DigestUtils.sha256Hex(source),
                "Audit hash-chain integrity failure",
                "Audit event hash validation failed; event access is quarantined", 1);
        log.error("Audit integrity failure detected: eventId={}", invalidEvent.getEventId());
    }

}
