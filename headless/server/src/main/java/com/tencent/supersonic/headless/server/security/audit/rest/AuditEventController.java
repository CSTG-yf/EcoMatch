package com.tencent.supersonic.headless.server.security.audit.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.security.audit.AuditAccessGuard;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.AuditEventService;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventQuery;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.security.audit.model.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/security/audit")
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditAccessGuard accessGuard;

    public AuditEventController(AuditEventService auditEventService,
            AuditEventPublisher auditEventPublisher, AuditAccessGuard accessGuard) {
        this.auditEventService = auditEventService;
        this.auditEventPublisher = auditEventPublisher;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/events")
    public PageResult<AuditEventDO> events(@ModelAttribute AuditEventQuery query,
            HttpServletRequest request, HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "AUDIT_EVENT_COLLECTION");
        String organizationScope = accessGuard.organizationScope(user);
        if (organizationScope != null) {
            query.setOrganizationId(organizationScope);
        }
        PageResult<AuditEventDO> result = auditEventService.page(query);
        if (organizationScope != null) {
            result.list().forEach(event -> accessGuard.requireOrganizationAccess(user,
                    event.getOrganizationId(), "AUDIT_EVENT_COLLECTION"));
        }
        recordAccess(user, "AUDIT_EVENT_PAGE", null);
        return result;
    }

    @GetMapping("/events/{eventId}")
    public AuditEventDO event(@PathVariable String eventId, HttpServletRequest request,
            HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "AUDIT_EVENT");
        AuditEventDO result = auditEventService.getByEventId(eventId);
        if (result != null) {
            accessGuard.requireOrganizationAccess(user, result.getOrganizationId(), "AUDIT_EVENT");
        }
        recordAccess(user, "AUDIT_EVENT", result == null ? null : result.getEventId());
        return result;
    }

    @GetMapping("/traces/{traceId}")
    public List<AuditEventDO> trace(@PathVariable String traceId, HttpServletRequest request,
            HttpServletResponse response) {
        User user = user(request, response);
        accessGuard.requireRead(user, "AUDIT_TRACE");
        List<AuditEventDO> result = auditEventService.trace(traceId);
        result.forEach(event -> accessGuard.requireOrganizationAccess(user,
                event.getOrganizationId(), "AUDIT_TRACE"));
        recordAccess(user, "AUDIT_TRACE", result.isEmpty() ? null : result.get(0).getTraceId());
        return result;
    }

    private void recordAccess(User user, String resourceType, String resourceId) {
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.AUDIT_ACCESSED).resourceType(resourceType)
                .resourceId(resourceId).outcome(AuditOutcome.SUCCESS).build(), user);
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
