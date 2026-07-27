package com.tencent.supersonic.headless.server.security.audit.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import com.tencent.supersonic.headless.server.security.audit.AuditAccessGuard;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.AuditEventService;
import com.tencent.supersonic.headless.server.security.audit.AuditRuleService;
import com.tencent.supersonic.headless.server.security.audit.SecurityAlertService;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDetail;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDispositionRequest;
import com.tencent.supersonic.headless.server.security.audit.model.AlertStatus;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventQuery;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.security.audit.model.AuditRuleRequest;
import com.tencent.supersonic.headless.server.security.audit.model.PageResult;
import com.tencent.supersonic.headless.server.security.audit.model.SecurityAlertQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditManagementControllerTest {

    private final AuditEventService auditEventService = mock(AuditEventService.class);
    private final AuditRuleService auditRuleService = mock(AuditRuleService.class);
    private final SecurityAlertService securityAlertService = mock(SecurityAlertService.class);
    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final User administrator = User.getDefaultUser();

    private MockedStatic<UserHolder> userHolder;
    private AuditEventController auditEventController;
    private AuditRuleController auditRuleController;
    private SecurityAlertController securityAlertController;

    @BeforeEach
    void setUp() {
        userHolder = mockStatic(UserHolder.class);
        userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(administrator);
        AuditAccessGuard guard = new AuditAccessGuard(publisher, "", "");
        auditEventController = new AuditEventController(auditEventService, publisher, guard);
        auditRuleController = new AuditRuleController(auditRuleService, publisher, guard);
        securityAlertController =
                new SecurityAlertController(securityAlertService, publisher, guard);
    }

    @AfterEach
    void tearDown() {
        userHolder.close();
    }

    @Test
    void auditPageReadIsRequiredAndDoesNotCopyQueryConditions() {
        AuditEventQuery query = new AuditEventQuery();
        query.setUserName("sensitive-user-filter");
        query.setOrganizationId("sensitive-organization-filter");
        query.setResourceId("sensitive-resource-filter");
        PageResult<AuditEventDO> expected = new PageResult<>(List.of(), 1, 20, 0);
        when(auditEventService.page(query)).thenReturn(expected);

        PageResult<AuditEventDO> actual = auditEventController.events(query, request, response);

        assertSame(expected, actual);
        AuditEvent event = captureSingleEvent();
        assertEquals(AuditEventType.AUDIT_ACCESSED, event.getEventType());
        assertEquals(AuditOutcome.SUCCESS, event.getOutcome());
        assertEquals("AUDIT_EVENT_PAGE", event.getResourceType());
        assertNoRequestData(event);
    }

    @Test
    void auditPageFailsClosedWhenDatabaseReturnsCaseInsensitiveOrganizationMatch() {
        User auditor = organizationAuditor("branch-a");
        userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(auditor);
        AuditEventQuery query = new AuditEventQuery();
        AuditEventDO mismatched = new AuditEventDO();
        mismatched.setOrganizationId("BRANCH-A");
        when(auditEventService.page(query))
                .thenReturn(new PageResult<>(List.of(mismatched), 1, 20, 1));

        assertThrows(InvalidPermissionException.class,
                () -> auditEventController.events(query, request, response));

        assertEquals("branch-a", query.getOrganizationId());
        AuditEvent denied = captureSingleEvent(auditor);
        assertEquals(AuditOutcome.DENIED, denied.getOutcome());
        assertEquals("AUDIT_ORGANIZATION_FORBIDDEN", denied.getReasonCode());
    }

    @Test
    void auditDetailAndTraceUseIdentifiersReturnedByService() {
        AuditEventDO storedEvent = new AuditEventDO();
        storedEvent.setEventId("event-1");
        storedEvent.setTraceId("trace-1");
        when(auditEventService.getByEventId("untrusted-event-input")).thenReturn(storedEvent);
        when(auditEventService.trace("untrusted-trace-input")).thenReturn(List.of(storedEvent));

        auditEventController.event("untrusted-event-input", request, response);
        auditEventController.trace("untrusted-trace-input", request, response);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher, times(2)).publishRequired(captor.capture(), eq(administrator));
        assertEquals("event-1", captor.getAllValues().get(0).getResourceId());
        assertEquals("trace-1", captor.getAllValues().get(1).getResourceId());
    }

    @Test
    void successfulReadFailsClosedWhenAuditWriteFails() {
        AuditEventQuery query = new AuditEventQuery();
        PageResult<AuditEventDO> result = new PageResult<>(List.of(), 1, 20, 0);
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(auditEventService.page(query)).thenReturn(result);
        when(publisher.publishRequired(any(), eq(administrator))).thenThrow(auditFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> auditEventController.events(query, request, response));

        assertSame(auditFailure, thrown);
    }

    @Test
    void ruleListAndSuccessfulChangeAreBothAudited() {
        AuditRuleDO rule = new AuditRuleDO();
        rule.setRuleCode("RULE_001");
        rule.setEnabled(true);
        when(auditRuleService.list()).thenReturn(List.of(rule));
        AuditRuleRequest createRequest = new AuditRuleRequest();
        when(auditRuleService.create(createRequest, administrator.getName())).thenReturn(rule);

        auditRuleController.rules(request, response);
        auditRuleController.create(createRequest, request, response);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher, times(2)).publishRequired(captor.capture(), eq(administrator));
        AuditEvent read = captor.getAllValues().get(0);
        assertEquals(AuditEventType.AUDIT_ACCESSED, read.getEventType());
        assertEquals("AUDIT_RULE_COLLECTION", read.getResourceType());
        assertNoRequestData(read);
        AuditEvent change = captor.getAllValues().get(1);
        assertEquals(AuditEventType.ALERT_RULE_CHANGED, change.getEventType());
        assertEquals("RULE_001", change.getResourceId());
        assertEquals("ENABLED", change.getReasonCode());
        assertNoRequestPayload(change);
    }

    @Test
    void deniedRuleChangeIsAuditedBeforeServiceInvocation() {
        User visitor = User.getVisitUser();
        userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(visitor);

        assertThrows(InvalidPermissionException.class,
                () -> auditRuleController.create(new AuditRuleRequest(), request, response));

        verify(auditRuleService, never()).create(any(), any());
        AuditEvent denied = captureSingleEvent(visitor);
        assertEquals(AuditEventType.ALERT_RULE_CHANGED, denied.getEventType());
        assertEquals(AuditOutcome.DENIED, denied.getOutcome());
        assertEquals("AUDIT_WRITE_FORBIDDEN", denied.getReasonCode());
        assertNoRequestData(denied);
    }

    @Test
    void successfulRuleChangeFailsClosedWhenAuditWriteFails() {
        AuditRuleRequest createRequest = new AuditRuleRequest();
        AuditRuleDO rule = new AuditRuleDO();
        rule.setRuleCode("RULE_001");
        rule.setEnabled(true);
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(auditRuleService.create(createRequest, administrator.getName())).thenReturn(rule);
        when(publisher.publishRequired(any(), eq(administrator))).thenThrow(auditFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> auditRuleController.create(createRequest, request, response));

        assertSame(auditFailure, thrown);
    }

    @Test
    void alertListAndDetailReadsAreBothAuditedWithoutFilters() {
        SecurityAlertQuery query = new SecurityAlertQuery();
        query.setUserName("sensitive-user-filter");
        query.setOrganizationId("sensitive-organization-filter");
        PageResult<SecurityAlertDO> page = new PageResult<>(List.of(), 1, 20, 0);
        when(securityAlertService.page(query)).thenReturn(page);
        SecurityAlertDO alert = alert("alert-1", AlertStatus.NEW);
        AlertDetail detail = new AlertDetail(alert, List.of(), List.of());
        when(securityAlertService.detail("untrusted-alert-input")).thenReturn(detail);

        securityAlertController.alerts(query, request, response);
        securityAlertController.detail("untrusted-alert-input", request, response);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher, times(2)).publishRequired(captor.capture(), eq(administrator));
        AuditEvent listRead = captor.getAllValues().get(0);
        assertEquals("SECURITY_ALERT_COLLECTION", listRead.getResourceType());
        assertNoRequestData(listRead);
        AuditEvent detailRead = captor.getAllValues().get(1);
        assertEquals("SECURITY_ALERT", detailRead.getResourceType());
        assertEquals("alert-1", detailRead.getResourceId());
    }

    @Test
    void alertPageFailsClosedWhenDatabaseReturnsCaseInsensitiveOrganizationMatch() {
        User auditor = organizationAuditor("branch-a");
        userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(auditor);
        SecurityAlertQuery query = new SecurityAlertQuery();
        SecurityAlertDO mismatched = alert("alert-1", AlertStatus.NEW);
        mismatched.setOrganizationId("BRANCH-A");
        when(securityAlertService.page(query))
                .thenReturn(new PageResult<>(List.of(mismatched), 1, 20, 1));

        assertThrows(InvalidPermissionException.class,
                () -> securityAlertController.alerts(query, request, response));

        assertEquals("branch-a", query.getOrganizationId());
        AuditEvent denied = captureSingleEvent(auditor);
        assertEquals(AuditOutcome.DENIED, denied.getOutcome());
        assertEquals("AUDIT_ORGANIZATION_FORBIDDEN", denied.getReasonCode());
    }

    @Test
    void alertTransitionUsesRequiredAuditAndReturnedAlertState() {
        AlertDispositionRequest disposition = new AlertDispositionRequest();
        disposition.setStatus(AlertStatus.ACKNOWLEDGED);
        SecurityAlertDO transitioned = alert("alert-1", AlertStatus.ACKNOWLEDGED);
        when(securityAlertService.transition("untrusted-alert-input", disposition,
                administrator.getName())).thenReturn(transitioned);

        SecurityAlertDO actual = securityAlertController.transition("untrusted-alert-input",
                disposition, request, response);

        assertSame(transitioned, actual);
        AuditEvent event = captureSingleEvent();
        assertEquals(AuditEventType.ALERT_STATUS_CHANGED, event.getEventType());
        assertEquals(AuditOutcome.SUCCESS, event.getOutcome());
        assertEquals("alert-1", event.getResourceId());
        assertEquals(AlertStatus.ACKNOWLEDGED.name(), event.getReasonCode());
        assertNoRequestPayload(event);
    }

    @Test
    void deniedAlertReadPreservesAuditFailureAsSuppressed() {
        User visitor = User.getVisitUser();
        userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(visitor);
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        when(publisher.publishRequired(any(), eq(visitor))).thenThrow(auditFailure);

        InvalidPermissionException denial = assertThrows(InvalidPermissionException.class,
                () -> securityAlertController.alerts(new SecurityAlertQuery(), request, response));

        assertEquals(1, denial.getSuppressed().length);
        assertSame(auditFailure, denial.getSuppressed()[0]);
        verify(securityAlertService, never()).page(any());
    }

    private SecurityAlertDO alert(String alertId, AlertStatus status) {
        SecurityAlertDO alert = new SecurityAlertDO();
        alert.setAlertId(alertId);
        alert.setStatus(status.name());
        return alert;
    }

    private User organizationAuditor(String organizationId) {
        User auditor = User.get(2L, "auditor");
        auditor.setRoles(Set.of("SECURITY_AUDITOR"));
        auditor.setAttributes(Map.of("organizationId", organizationId));
        return auditor;
    }

    private AuditEvent captureSingleEvent() {
        return captureSingleEvent(administrator);
    }

    private AuditEvent captureSingleEvent(User user) {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishRequired(captor.capture(), eq(user));
        return captor.getValue();
    }

    private void assertNoRequestData(AuditEvent event) {
        assertNull(event.getResourceId());
        assertNoRequestPayload(event);
    }

    private void assertNoRequestPayload(AuditEvent event) {
        assertNull(event.getRawQuestion());
        assertNull(event.getRawSql());
        assertNull(event.getMetadata());
        assertNull(event.getMetricCodes());
        assertNull(event.getPolicyIds());
    }
}
