package com.tencent.supersonic.headless.server.security.audit.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import com.tencent.supersonic.headless.server.security.audit.AuditAccessGuard;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.SecurityAlertService;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDetail;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDispositionRequest;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.security.audit.model.PageResult;
import com.tencent.supersonic.headless.server.security.audit.model.SecurityAlertQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security/alerts")
public class SecurityAlertController {

    private final SecurityAlertService securityAlertService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditAccessGuard accessGuard;

    public SecurityAlertController(SecurityAlertService securityAlertService,
            AuditEventPublisher auditEventPublisher, AuditAccessGuard accessGuard) {
        this.securityAlertService = securityAlertService;
        this.auditEventPublisher = auditEventPublisher;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public PageResult<SecurityAlertDO> alerts(@ModelAttribute SecurityAlertQuery query,
            HttpServletRequest request, HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "SECURITY_ALERT_COLLECTION");
        String organizationScope = accessGuard.organizationScope(user);
        if (organizationScope != null) {
            query.setOrganizationId(organizationScope);
        }
        PageResult<SecurityAlertDO> alerts = securityAlertService.page(query);
        if (organizationScope != null) {
            alerts.list().forEach(alert -> accessGuard.requireOrganizationAccess(user,
                    alert.getOrganizationId(), "SECURITY_ALERT_COLLECTION"));
        }
        recordRead(user, "SECURITY_ALERT_COLLECTION", null);
        return alerts;
    }

    @GetMapping("/{alertId}")
    public AlertDetail detail(@PathVariable String alertId, HttpServletRequest request,
            HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "SECURITY_ALERT");
        AlertDetail detail = securityAlertService.detail(alertId);
        accessGuard.requireOrganizationAccess(user, detail.alert().getOrganizationId(),
                "SECURITY_ALERT");
        recordRead(user, "SECURITY_ALERT", detail.alert().getAlertId());
        return detail;
    }

    @PutMapping("/{alertId}/status")
    @Transactional
    public SecurityAlertDO transition(@PathVariable String alertId,
            @RequestBody AlertDispositionRequest disposition, HttpServletRequest request,
            HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireWrite(user, AuditEventType.ALERT_STATUS_CHANGED, "SECURITY_ALERT");
        String organizationScope = accessGuard.organizationScope(user);
        if (organizationScope != null) {
            SecurityAlertDO existing = securityAlertService.getByAlertId(alertId);
            accessGuard.requireOrganizationAccess(user, existing.getOrganizationId(),
                    "SECURITY_ALERT");
        }
        SecurityAlertDO alert =
                securityAlertService.transition(alertId, disposition, user.getName());
        auditEventPublisher
                .publishRequired(AuditEvent.builder().eventType(AuditEventType.ALERT_STATUS_CHANGED)
                        .resourceType("SECURITY_ALERT").resourceId(alert.getAlertId())
                        .outcome(AuditOutcome.SUCCESS).reasonCode(alert.getStatus()).build(), user);
        return alert;
    }

    private void recordRead(User user, String resourceType, String resourceId) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.AUDIT_ACCESSED).resourceType(resourceType)
                .resourceId(resourceId).outcome(AuditOutcome.SUCCESS).build(), user);
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
