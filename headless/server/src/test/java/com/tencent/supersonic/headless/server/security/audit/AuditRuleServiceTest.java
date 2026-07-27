package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.AuditRuleDO;
import com.tencent.supersonic.headless.server.persistence.mapper.AuditRuleMapper;
import com.tencent.supersonic.headless.server.security.audit.model.AlertRuleType;
import com.tencent.supersonic.headless.server.security.audit.model.AlertSeverity;
import com.tencent.supersonic.headless.server.security.audit.model.AuditRuleRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditRuleServiceTest {

    private final AuditRuleMapper mapper = mock(AuditRuleMapper.class);
    private final AuditRuleService service = new AuditRuleService(mapper, new ObjectMapper());

    @Test
    void requiresVersionForUpdate() {
        AuditRuleRequest request = request(AlertRuleType.HIGH_FREQUENCY_QUERY);

        assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, request, "security-admin"));

        verifyNoInteractions(mapper);
    }

    @Test
    void updatesRuleWithExplicitCompareAndSet() {
        AuditRuleRequest request = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        request.setVersion(2);
        AuditRuleDO existing = persistedRule(2);
        AuditRuleDO changed = persistedRule(3);
        changed.setRuleName("Changed rule");
        when(mapper.selectById(7L)).thenReturn(existing, changed);
        when(mapper.compareAndSet(any(AuditRuleDO.class), eq(2))).thenReturn(1);

        AuditRuleDO result = service.update(7L, request, "security-admin");

        assertEquals(3, result.getVersion());
        ArgumentCaptor<AuditRuleDO> update = ArgumentCaptor.forClass(AuditRuleDO.class);
        verify(mapper).compareAndSet(update.capture(), eq(2));
        assertEquals(7L, update.getValue().getId());
        assertEquals("security-admin", update.getValue().getUpdatedBy());
    }

    @Test
    void rejectsStaleOrLostCompareAndSet() {
        AuditRuleRequest request = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        request.setVersion(1);
        when(mapper.selectById(7L)).thenReturn(persistedRule(2));

        assertThrows(IllegalStateException.class,
                () -> service.update(7L, request, "security-admin"));
        verify(mapper, never()).compareAndSet(any(), any());

        request.setVersion(2);
        when(mapper.compareAndSet(any(AuditRuleDO.class), eq(2))).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> service.update(7L, request, "security-admin"));
    }

    @Test
    void enforcesThresholdAndWindowBounds() {
        AuditRuleRequest threshold = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        threshold.setThresholdValue(AuditRuleService.MAXIMUM_THRESHOLD_VALUE + 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(threshold, "security-admin"));

        AuditRuleRequest window = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        window.setWindowSeconds(AuditRuleService.MAXIMUM_WINDOW_SECONDS + 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(window, "security-admin"));
    }

    @Test
    void requiresStrictWorkHours() {
        AuditRuleRequest request = request(AlertRuleType.OFF_HOURS_ACCESS);
        request.setWindowSeconds(0L);
        request.setWorkHoursStart("07:00:00");
        request.setWorkHoursEnd("22:00");

        assertThrows(IllegalArgumentException.class,
                () -> service.create(request, "security-admin"));
    }

    @Test
    void acceptsOnlyBoundedBulkExportRowThreshold() {
        AuditRuleRequest unrelated = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        unrelated.setConfigJson("{\"rowThreshold\":10000}");
        assertThrows(IllegalArgumentException.class,
                () -> service.create(unrelated, "security-admin"));

        AuditRuleRequest unsupported = request(AlertRuleType.BULK_EXPORT);
        unsupported.setConfigJson("{\"operationThreshold\":5}");
        assertThrows(IllegalArgumentException.class,
                () -> service.create(unsupported, "security-admin"));

        AuditRuleRequest decimal = request(AlertRuleType.BULK_EXPORT);
        decimal.setConfigJson("{\"rowThreshold\":1.5}");
        assertThrows(IllegalArgumentException.class,
                () -> service.create(decimal, "security-admin"));

        AuditRuleRequest excessive = request(AlertRuleType.BULK_EXPORT);
        excessive.setConfigJson(
                "{\"rowThreshold\":" + (AuditRuleService.MAXIMUM_ROW_THRESHOLD + 1) + "}");
        assertThrows(IllegalArgumentException.class,
                () -> service.create(excessive, "security-admin"));
    }

    @Test
    void storesCanonicalValidatedConfiguration() {
        AuditRuleRequest request = request(AlertRuleType.BULK_EXPORT);
        request.setConfigJson(" { \"rowThreshold\" : 10000 } ");
        when(mapper.selectOne(any())).thenReturn(null);

        service.create(request, "security-admin");

        ArgumentCaptor<AuditRuleDO> inserted = ArgumentCaptor.forClass(AuditRuleDO.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("{\"rowThreshold\":10000}", inserted.getValue().getConfigJson());
    }

    @Test
    void validatesNamesOperatorsAndConfigurationLength() {
        AuditRuleRequest longName = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        longName.setRuleName("x".repeat(129));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(longName, "security-admin"));

        AuditRuleRequest valid = request(AlertRuleType.HIGH_FREQUENCY_QUERY);
        assertThrows(IllegalArgumentException.class, () -> service.create(valid, "x".repeat(129)));

        AuditRuleRequest longJson = request(AlertRuleType.BULK_EXPORT);
        longJson.setConfigJson(" ".repeat(4_097));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(longJson, "security-admin"));
    }

    @Test
    void ignoresConcurrentDefaultRuleInsertConflict() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AuditRuleDO.class))).thenThrow(new DuplicateKeyException("race"))
                .thenReturn(1, 1, 1);

        assertDoesNotThrow(service::initializeDefaults);
    }

    private AuditRuleRequest request(AlertRuleType type) {
        AuditRuleRequest request = new AuditRuleRequest();
        request.setRuleCode("TEST_RULE");
        request.setRuleName("Test rule");
        request.setRuleType(type);
        request.setThresholdValue(3L);
        request.setWindowSeconds(60L);
        request.setSeverity(AlertSeverity.HIGH);
        request.setEnabled(true);
        return request;
    }

    private AuditRuleDO persistedRule(int version) {
        AuditRuleDO rule = new AuditRuleDO();
        rule.setId(7L);
        rule.setRuleCode("TEST_RULE");
        rule.setVersion(version);
        return rule;
    }
}
