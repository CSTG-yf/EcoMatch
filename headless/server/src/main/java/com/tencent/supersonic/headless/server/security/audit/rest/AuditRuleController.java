package com.tencent.supersonic.headless.server.security.audit.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.security.audit.AuditAccessGuard;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.AuditRuleService;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.security.audit.model.AuditRuleRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/security/audit/rules")
public class AuditRuleController {

    private final AuditRuleService auditRuleService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditAccessGuard accessGuard;

    public AuditRuleController(AuditRuleService auditRuleService,
            AuditEventPublisher auditEventPublisher, AuditAccessGuard accessGuard) {
        this.auditRuleService = auditRuleService;
        this.auditEventPublisher = auditEventPublisher;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public List<AuditRuleDO> rules(HttpServletRequest request, HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "AUDIT_RULE_COLLECTION");
        List<AuditRuleDO> rules = auditRuleService.list();
        recordRead(user);
        return rules;
    }

    @PostMapping
    @Transactional
    public AuditRuleDO create(@RequestBody AuditRuleRequest ruleRequest, HttpServletRequest request,
            HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireGlobalWrite(user, AuditEventType.ALERT_RULE_CHANGED, "AUDIT_RULE");
        AuditRuleDO rule = auditRuleService.create(ruleRequest, user.getName());
        recordChange(user, rule);
        return rule;
    }

    @PutMapping("/{id}")
    @Transactional
    public AuditRuleDO update(@PathVariable Long id, @RequestBody AuditRuleRequest ruleRequest,
            HttpServletRequest request, HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireGlobalWrite(user, AuditEventType.ALERT_RULE_CHANGED, "AUDIT_RULE");
        AuditRuleDO rule = auditRuleService.update(id, ruleRequest, user.getName());
        recordChange(user, rule);
        return rule;
    }

    private void recordRead(User user) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.AUDIT_ACCESSED).resourceType("AUDIT_RULE_COLLECTION")
                .outcome(AuditOutcome.SUCCESS).build(), user);
    }

    private void recordChange(User user, AuditRuleDO rule) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.ALERT_RULE_CHANGED).resourceType("AUDIT_RULE")
                .resourceId(rule.getRuleCode()).outcome(AuditOutcome.SUCCESS)
                .reasonCode(rule.getEnabled() ? "ENABLED" : "DISABLED").build(), user);
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
