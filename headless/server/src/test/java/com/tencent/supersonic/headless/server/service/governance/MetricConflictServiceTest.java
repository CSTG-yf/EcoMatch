package com.tencent.supersonic.headless.server.service.governance;

import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import com.tencent.supersonic.headless.server.pojo.governance.MetricConflict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricConflictServiceTest {

    private final MetricConflictService service = new MetricConflictService(null, null);

    @Test
    void shouldDetectExternalCodeCollisionWithinOrganization() {
        List<MetricConflict> conflicts = service
                .detect(Arrays.asList(mapping(1L, 101L, "ORG001", "DEP001", "存款余额", "期末存款余额"),
                        mapping(2L, 102L, "ORG001", "DEP001", "存款余额", "期末存款余额")));

        assertTrue(
                conflicts.stream().anyMatch(item -> "EXTERNAL_CODE_COLLISION".equals(item.getType())
                        && "HIGH".equals(item.getSeverity())));
    }

    @Test
    void shouldDetectCrossOrganizationDefinitionMismatch() {
        List<MetricConflict> conflicts = service
                .detect(Arrays.asList(mapping(1L, 101L, "ORG001", "DEP001", "存款余额", "含应计利息的期末余额"),
                        mapping(2L, 101L, "ORG002", "DEP001", "存款余额", "不含应计利息的期末余额")));

        assertTrue(conflicts.stream().anyMatch(item -> "DEFINITION_MISMATCH".equals(item.getType())
                && item.getMetricIds().equals(java.util.Collections.singletonList(101L))));
    }

    @Test
    void shouldIgnoreFormattingOnlyDefinitionDifferences() {
        List<MetricConflict> conflicts = service
                .detect(Arrays.asList(mapping(1L, 101L, "ORG001", "DEP001", "存款余额", "期末 存款余额。"),
                        mapping(2L, 101L, "ORG002", "DEP001", "存款余额", "期末存款余额")));

        assertEquals(0, conflicts.stream()
                .filter(item -> "DEFINITION_MISMATCH".equals(item.getType())).count());
    }

    @Test
    void shouldDetectExternalNameMismatch() {
        List<MetricConflict> conflicts =
                service.detect(Arrays.asList(mapping(1L, 101L, "ORG001", "DEP001", "存款余额", "期末余额"),
                        mapping(2L, 101L, "ORG002", "DEP001", "各项存款", "期末余额")));

        assertTrue(
                conflicts.stream().anyMatch(item -> "EXTERNAL_NAME_MISMATCH".equals(item.getType())
                        && "MEDIUM".equals(item.getSeverity())));
    }

    private MetricOrgMappingDO mapping(Long id, Long metricId, String organization,
            String externalCode, String externalName, String definition) {
        MetricOrgMappingDO mapping = new MetricOrgMappingDO();
        mapping.setId(id);
        mapping.setMetricId(metricId);
        mapping.setOrganizationCode(organization);
        mapping.setExternalMetricCode(externalCode);
        mapping.setExternalMetricName(externalName);
        mapping.setBusinessDefinition(definition);
        mapping.setMappingStatus("ACTIVE");
        return mapping;
    }
}
