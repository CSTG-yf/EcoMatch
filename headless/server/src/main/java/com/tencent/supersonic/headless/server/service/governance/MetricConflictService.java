package com.tencent.supersonic.headless.server.service.governance;

import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import com.tencent.supersonic.headless.server.pojo.governance.MetricConflict;
import com.tencent.supersonic.headless.server.service.MetricService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MetricConflictService {

    private final MetricService metricService;
    private final MetricGovernanceService governanceService;

    public MetricConflictService(MetricService metricService,
            MetricGovernanceService governanceService) {
        this.metricService = metricService;
        this.governanceService = governanceService;
    }

    public List<MetricConflict> detectByModel(Long modelId) {
        List<MetricResp> metrics = metricService
                .getMetrics(new MetaFilter(java.util.Collections.singletonList(modelId)));
        List<Long> metricIds = metrics.stream().map(MetricResp::getId).collect(Collectors.toList());
        return detect(governanceService.listMappings(metricIds));
    }

    public List<MetricConflict> detect(List<MetricOrgMappingDO> mappings) {
        List<MetricOrgMappingDO> active = mappings.stream()
                .filter(item -> !"INACTIVE".equalsIgnoreCase(item.getMappingStatus()))
                .collect(Collectors.toList());
        List<MetricConflict> conflicts = new ArrayList<>();
        detectExternalCodeCollisions(active, conflicts);
        detectDefinitionMismatches(active, conflicts);
        detectNameMismatches(active, conflicts);
        return conflicts;
    }

    private void detectExternalCodeCollisions(List<MetricOrgMappingDO> mappings,
            List<MetricConflict> conflicts) {
        Map<String, List<MetricOrgMappingDO>> grouped = mappings.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getOrganizationCode() + "|" + item.getExternalMetricCode(),
                        LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((key, values) -> {
            Set<Long> metricIds = values.stream().map(MetricOrgMappingDO::getMetricId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (metricIds.size() > 1) {
                conflicts.add(conflict("EXTERNAL_CODE_COLLISION", "HIGH", key,
                        "同一机构的外部指标编号映射到多个标准指标", metricIds, values));
            }
        });
    }

    private void detectDefinitionMismatches(List<MetricOrgMappingDO> mappings,
            List<MetricConflict> conflicts) {
        Map<Long, List<MetricOrgMappingDO>> grouped =
                mappings.stream().collect(Collectors.groupingBy(MetricOrgMappingDO::getMetricId,
                        LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((metricId, values) -> {
            Set<String> definitions = values.stream().map(MetricOrgMappingDO::getBusinessDefinition)
                    .filter(StringUtils::isNotBlank).map(this::normalize)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (definitions.size() > 1) {
                conflicts.add(conflict("DEFINITION_MISMATCH", "HIGH", String.valueOf(metricId),
                        "同一标准指标在不同机构存在不一致的计算口径", java.util.Collections.singleton(metricId),
                        values));
            }
        });
    }

    private void detectNameMismatches(List<MetricOrgMappingDO> mappings,
            List<MetricConflict> conflicts) {
        Map<String, List<MetricOrgMappingDO>> grouped = mappings.stream()
                .collect(Collectors.groupingBy(MetricOrgMappingDO::getExternalMetricCode,
                        LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((code, values) -> {
            Set<String> names = values.stream().map(MetricOrgMappingDO::getExternalMetricName)
                    .filter(StringUtils::isNotBlank).map(this::normalize)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (names.size() > 1) {
                Set<Long> metricIds = values.stream().map(MetricOrgMappingDO::getMetricId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                conflicts.add(conflict("EXTERNAL_NAME_MISMATCH", "MEDIUM", code,
                        "相同外部指标编号在不同机构使用了不一致的名称", metricIds, values));
            }
        });
    }

    private MetricConflict conflict(String type, String severity, String key, String message,
            Set<Long> metricIds, List<MetricOrgMappingDO> mappings) {
        return new MetricConflict(type, severity, key, message, new ArrayList<>(metricIds),
                mappings.stream().map(MetricOrgMappingDO::getId).collect(Collectors.toList()));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s，。；：、,.;:()（）_-]+", "");
    }
}
