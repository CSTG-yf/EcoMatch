package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.TraceIdUtil;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditEventDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditEventMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventServiceTest {

    private final AuditEventMapper mapper = mock(AuditEventMapper.class);
    private final AuditEventMutationService mutationService = new AuditEventMutationService(mapper);
    private final AuditEventService service =
            new AuditEventService(mapper, new AuditSanitizer(new ObjectMapper()),
                    new AuditProperties(true, "Asia/Shanghai", 100, 500), mutationService);

    @AfterEach
    void clearTrace() {
        TraceIdUtil.remove();
    }

    @Test
    void persistsMaskedQuestionAndSqlDigestWithoutRawSql() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AuditEventDO.class))).thenReturn(1);
        TraceIdUtil.setTraceId("trace_test_1");
        User user = User.get(9L, "analyst");
        user.setAttributes(Map.of("organizationId", " branch-001 "));
        String rawQuestion = "查询账号622200001234的余额";
        String rawSql = "SELECT balance FROM account WHERE account_no='622200001234'";

        AuditEventDO persisted =
                service.persist(
                        AuditEvent.builder().eventType(AuditEventType.QUERY_SUCCEEDED)
                                .outcome(AuditOutcome.SUCCESS).rawQuestion(rawQuestion)
                                .rawSql(rawSql).resourceType("MODEL").resourceId("12").build(),
                        user);

        ArgumentCaptor<AuditEventDO> captor = ArgumentCaptor.forClass(AuditEventDO.class);
        verify(mapper).insert(captor.capture());
        AuditEventDO stored = captor.getValue();
        assertEquals(persisted, stored);
        assertEquals("trace_test_1", stored.getTraceId());
        assertEquals("analyst", stored.getUserName());
        assertEquals("branch-001", stored.getOrganizationId());
        assertEquals("SELECT", stored.getSqlType());
        assertEquals(64, stored.getSqlDigest().length());
        assertFalse(stored.getSanitizedQuestion().contains("622200001234"));
        assertFalse(stored.getMetadataJson() != null && stored.getMetadataJson().contains(rawSql));
        assertNotNull(stored.getEventHash());
        assertTrue(mutationService.hasValidHash(stored));
        stored.setReasonCode("tampered");
        assertFalse(mutationService.hasValidHash(stored));
    }

    @Test
    void retriesConcurrentTraceAppendAgainstLatestCommittedHash() {
        AuditEventDO firstHead = new AuditEventDO();
        firstHead.setEventHash("first-head");
        AuditEventDO latestHead = new AuditEventDO();
        latestHead.setEventHash("latest-head");
        when(mapper.selectOne(any())).thenReturn(firstHead, latestHead);
        when(mapper.insert(any(AuditEventDO.class)))
                .thenThrow(new DuplicateKeyException("concurrent append")).thenReturn(1);

        AuditEventDO persisted = service.persist(
                AuditEvent.builder().eventType(AuditEventType.QUERY_STARTED)
                        .outcome(AuditOutcome.UNKNOWN).traceId("shared-trace").build(),
                User.get(9L, "analyst"));

        assertEquals("latest-head", persisted.getPreviousHash());
        assertTrue(mutationService.hasValidHash(persisted));
        verify(mapper, times(2)).insert(any(AuditEventDO.class));
    }
}
