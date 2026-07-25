package com.tencent.supersonic.headless.server.security.audit;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditAccessGuardTest {

    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
    private final AuditAccessGuard guard =
            new AuditAccessGuard(publisher, "security-admin", "configured-auditor");

    @Test
    void acceptsNormalizedRoleAndConfiguredUserFallback() {
        User roleAuditor = User.get(1L, "role-auditor");
        roleAuditor.setRoles(Set.of(" risk_auditor "));
        User configuredAdmin = User.get(2L, "security-admin");

        guard.requireRead(roleAuditor, "AUDIT_EVENT_COLLECTION");
        guard.requireRead(configuredAdmin, "AUDIT_EVENT_COLLECTION");
        guard.requireWrite(configuredAdmin, AuditEventType.ALERT_RULE_CHANGED, "AUDIT_RULE");

        verifyNoInteractions(publisher);
    }

    @Test
    void configuredUserNamesAreCaseSensitive() {
        User differentCase = User.get(1L, "Security-Admin");

        assertThrows(InvalidPermissionException.class, () -> guard.requireWrite(differentCase,
                AuditEventType.ALERT_RULE_CHANGED, "AUDIT_RULE"));
    }

    @Test
    void emptyConfiguredUserListsDoNotGrantAccess() {
        AuditAccessGuard emptyGuard = new AuditAccessGuard(publisher, " , ", "");
        User user = User.get(1L, "configured-auditor");

        assertThrows(InvalidPermissionException.class,
                () -> emptyGuard.requireRead(user, "AUDIT_EVENT_COLLECTION"));
    }

    @Test
    void deniedWritePublishesStableRequiredEventWithoutRequestData() {
        User auditor = User.get(1L, "configured-auditor");

        assertThrows(InvalidPermissionException.class,
                () -> guard.requireWrite(auditor, AuditEventType.ALERT_RULE_CHANGED, "AUDIT_RULE"));

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishRequired(eventCaptor.capture(), eq(auditor));
        AuditEvent event = eventCaptor.getValue();
        assertEquals(AuditEventType.ALERT_RULE_CHANGED, event.getEventType());
        assertEquals(AuditOutcome.DENIED, event.getOutcome());
        assertEquals("AUDIT_WRITE_FORBIDDEN", event.getReasonCode());
        assertEquals("AUDIT_RULE", event.getResourceType());
        assertNull(event.getResourceId());
        assertNull(event.getRawQuestion());
        assertNull(event.getRawSql());
        assertNull(event.getMetadata());
    }

    @Test
    void auditFailureIsSuppressedWithoutReplacingPermissionDenial() {
        User visitor = User.getVisitUser();
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(publisher.publishRequired(any(), eq(visitor))).thenThrow(auditFailure);

        InvalidPermissionException denial = assertThrows(InvalidPermissionException.class,
                () -> guard.requireRead(visitor, "SECURITY_ALERT_COLLECTION"));

        assertEquals(1, denial.getSuppressed().length);
        assertSame(auditFailure, denial.getSuppressed()[0]);
    }

    @Test
    void organizationAttributeRestrictsNonGlobalRole() {
        User auditor = User.get(1L, "role-auditor");
        auditor.setRoles(Set.of("SECURITY_AUDITOR"));
        auditor.setAttributes(Map.of("organizationId", "branch-001"));

        guard.requireRead(auditor, "AUDIT_EVENT_COLLECTION");
        guard.requireOrganizationAccess(auditor, "branch-001", "AUDIT_EVENT");
        InvalidPermissionException denial = assertThrows(InvalidPermissionException.class,
                () -> guard.requireOrganizationAccess(auditor, "branch-002", "AUDIT_EVENT"));

        assertEquals("branch-001", guard.organizationScope(auditor));
        assertEquals("Audit data is outside the user's organization scope", denial.getMessage());
        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishRequired(eventCaptor.capture(), eq(auditor));
        assertEquals("AUDIT_ORGANIZATION_FORBIDDEN", eventCaptor.getValue().getReasonCode());
        assertNull(eventCaptor.getValue().getResourceId());
    }

    @Test
    void nonGlobalRoleCannotReadDataWithoutOrganizationAttribute() {
        User auditor = User.get(1L, "role-auditor");
        auditor.setRoles(Set.of("SECURITY_AUDITOR"));

        InvalidPermissionException denial = assertThrows(InvalidPermissionException.class,
                () -> guard.organizationScope(auditor));

        assertEquals("A non-global audit role must have a trusted organization attribute",
                denial.getMessage());
    }

    @Test
    void organizationScopedAdministratorCannotModifyGlobalRules() {
        User administrator = User.get(1L, "branch-security-admin");
        administrator.setRoles(Set.of("SECURITY_ADMIN"));
        administrator.setAttributes(Map.of("organizationId", "branch-001"));

        InvalidPermissionException denial = assertThrows(InvalidPermissionException.class,
                () -> guard.requireGlobalWrite(administrator, AuditEventType.ALERT_RULE_CHANGED,
                        "AUDIT_RULE"));

        assertEquals("Only a global security administrator can modify audit rules",
                denial.getMessage());
    }
}
