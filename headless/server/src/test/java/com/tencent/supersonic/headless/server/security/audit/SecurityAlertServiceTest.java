package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AlertActionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SecurityAlertDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AlertActionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SecurityAlertMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertDispositionRequest;
import com.tencent.supersonic.headless.server.security.audit.model.AlertStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityAlertServiceTest {

    private final SecurityAlertMapper alertMapper = mock(SecurityAlertMapper.class);
    private final AlertActionMapper actionMapper = mock(AlertActionMapper.class);
    private final AuditEventService eventService = mock(AuditEventService.class);
    private final SecurityAlertMutationService mutationService =
            mock(SecurityAlertMutationService.class);
    private final AuditSanitizer sanitizer = new AuditSanitizer(new ObjectMapper());
    private final SecurityAlertService service = new SecurityAlertService(alertMapper, actionMapper,
            eventService, new AuditProperties(true, "Asia/Shanghai", 100, 500), sanitizer,
            mutationService);

    @Test
    void recordsImmutableDispositionActionDuringValidTransition() {
        SecurityAlertDO current = alert(AlertStatus.ACKNOWLEDGED, 2);
        SecurityAlertDO resolved = alert(AlertStatus.RESOLVED, 3);
        when(alertMapper.selectOne(any())).thenReturn(current, resolved);
        when(alertMapper.transitionStatus(anyLong(), anyInt(), anyInt(), anyString(), any(),
                anyString())).thenReturn(1);
        AlertDispositionRequest request = new AlertDispositionRequest();
        request.setStatus(AlertStatus.RESOLVED);
        request.setComment("Confirmed with the branch and access was legitimate");
        request.setVersion(2);

        SecurityAlertDO result = service.transition("alert-1", request, "security-admin");

        assertEquals(AlertStatus.RESOLVED.name(), result.getStatus());
        ArgumentCaptor<AlertActionDO> action = ArgumentCaptor.forClass(AlertActionDO.class);
        verify(actionMapper).insert(action.capture());
        assertEquals(AlertStatus.ACKNOWLEDGED.name(), action.getValue().getFromStatus());
        assertEquals(AlertStatus.RESOLVED.name(), action.getValue().getToStatus());
        assertEquals("security-admin", action.getValue().getOperatorName());
    }

    @Test
    void rejectsTransitionFromTerminalStatus() {
        when(alertMapper.selectOne(any())).thenReturn(alert(AlertStatus.CLOSED, 4));
        AlertDispositionRequest request = new AlertDispositionRequest();
        request.setStatus(AlertStatus.ACKNOWLEDGED);

        assertThrows(IllegalArgumentException.class,
                () -> service.transition("alert-1", request, "security-admin"));
    }

    @Test
    void retriesEvidenceUpdateAfterConcurrentModification() {
        SecurityAlertDO initial = alert(AlertStatus.NEW, 1);
        initial.setFingerprint("fingerprint");
        initial.setOccurrenceCount(1L);
        initial.setEvidenceIds("event-1");
        SecurityAlertDO refreshed = alert(AlertStatus.NEW, 2);
        refreshed.setFingerprint("fingerprint");
        refreshed.setOccurrenceCount(2L);
        refreshed.setEvidenceIds("event-1,event-2");
        SecurityAlertDO updated = alert(AlertStatus.NEW, 3);
        when(alertMapper.selectOne(any())).thenReturn(initial, refreshed, updated);
        when(mutationService.updateEvidence(anyLong(), anyInt(), anyInt(), anyLong(), any(),
                anyString(), anyString(), any())).thenReturn(0, 1);

        SecurityAlertDO result =
                service.upsert(rule(), event("event-3"), "fingerprint", "title", "description", 3);

        assertEquals(3, result.getVersion());
        verify(mutationService, times(2)).updateEvidence(anyLong(), anyInt(), anyInt(), anyLong(),
                any(), anyString(), anyString(), any());
    }

    @Test
    void recoversFromConcurrentFirstInsertInANewTransaction() {
        SecurityAlertDO concurrent = alert(AlertStatus.NEW, 0);
        concurrent.setFingerprint("fingerprint");
        concurrent.setOccurrenceCount(1L);
        concurrent.setEvidenceIds("event-1");
        SecurityAlertDO updated = alert(AlertStatus.NEW, 1);
        when(alertMapper.selectOne(any())).thenReturn(null, concurrent, updated);
        when(mutationService.insert(any(SecurityAlertDO.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mutationService.updateEvidence(anyLong(), anyInt(), anyInt(), anyLong(), any(),
                anyString(), anyString(), any())).thenReturn(1);

        SecurityAlertDO result =
                service.upsert(rule(), event("event-2"), "fingerprint", "title", "description", 2);

        assertEquals(1, result.getVersion());
        verify(mutationService).insert(any(SecurityAlertDO.class));
        verify(mutationService).updateEvidence(anyLong(), anyInt(), anyInt(), anyLong(), any(),
                anyString(), anyString(), any());
    }

    @Test
    void incrementsOccurrenceCountForEachNewEvidenceEvent() {
        SecurityAlertDO initial = alert(AlertStatus.NEW, 1);
        initial.setFingerprint("fingerprint");
        initial.setOccurrenceCount(1L);
        initial.setEvidenceIds("event-1");
        SecurityAlertDO updated = alert(AlertStatus.NEW, 2);
        when(alertMapper.selectOne(any())).thenReturn(initial, updated);
        when(mutationService.updateEvidence(anyLong(), anyInt(), anyInt(), anyLong(), any(),
                anyString(), anyString(), any())).thenReturn(1);

        service.upsert(rule(), event("event-2"), "fingerprint", "title", "description", 1);

        ArgumentCaptor<Long> count = ArgumentCaptor.forClass(Long.class);
        verify(mutationService).updateEvidence(anyLong(), anyInt(), anyInt(), count.capture(),
                any(), anyString(), anyString(), any());
        assertEquals(2L, count.getValue());
    }

    @Test
    void sanitizesDispositionCommentBeforePersistence() {
        SecurityAlertDO current = alert(AlertStatus.ACKNOWLEDGED, 2);
        SecurityAlertDO resolved = alert(AlertStatus.RESOLVED, 3);
        when(alertMapper.selectOne(any())).thenReturn(current, resolved);
        when(alertMapper.transitionStatus(anyLong(), anyInt(), anyInt(), anyString(), any(),
                anyString())).thenReturn(1);
        AlertDispositionRequest request = new AlertDispositionRequest();
        request.setStatus(AlertStatus.RESOLVED);
        request.setComment("Authorization: Bearer highly-sensitive-token");
        request.setVersion(2);

        service.transition("alert-1", request, "security-admin");

        ArgumentCaptor<AlertActionDO> action = ArgumentCaptor.forClass(AlertActionDO.class);
        verify(actionMapper).insert(action.capture());
        assertEquals("Authorization: ***", action.getValue().getComment());
    }

    private SecurityAlertDO alert(AlertStatus status, int version) {
        SecurityAlertDO alert = new SecurityAlertDO();
        alert.setId(1L);
        alert.setAlertId("alert-1");
        alert.setStatus(status.name());
        alert.setVersion(version);
        return alert;
    }

    private AuditRuleDO rule() {
        AuditRuleDO rule = new AuditRuleDO();
        rule.setId(1L);
        rule.setRuleCode("rule");
        rule.setSeverity("HIGH");
        return rule;
    }

    private AuditEventDO event(String eventId) {
        AuditEventDO event = new AuditEventDO();
        event.setEventId(eventId);
        event.setTraceId("trace");
        return event;
    }
}
