package com.tencent.supersonic.headless.server.service.governance;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.pojo.governance.MetricLineage;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MetricLineageService {

    private final MetricService metricService;
    private final ModelService modelService;
    private final DataSetService dataSetService;
    private final MetricGovernanceService governanceService;

    public MetricLineageService(MetricService metricService, ModelService modelService,
            DataSetService dataSetService, MetricGovernanceService governanceService) {
        this.metricService = metricService;
        this.modelService = modelService;
        this.dataSetService = dataSetService;
        this.governanceService = governanceService;
    }

    public MetricLineage getLineage(Long metricId, User user) {
        MetricResp metric = metricService.getMetric(metricId, user);
        if (metric == null) {
            throw new InvalidArgumentException("指标不存在");
        }
        ModelResp model = modelService.getModel(metric.getModelId());
        List<Long> domainModelIds =
                modelService.getModelByDomainIds(Collections.singletonList(model.getDomainId()))
                        .stream().map(ModelResp::getId).collect(Collectors.toList());
        List<MetricResp> domainMetrics = metricService.getMetrics(new MetaFilter(domainModelIds));

        MetricLineage lineage = new MetricLineage();
        lineage.setMetricId(metricId);
        lineage.setModelId(metric.getModelId());
        if (MetricDefineType.METRIC.equals(metric.getMetricDefineType())
                && metric.getMetricDefineByMetricParams() != null
                && metric.getMetricDefineByMetricParams().getMetrics() != null) {
            lineage.setUpstreamMetricIds(metric.getMetricDefineByMetricParams().getMetrics()
                    .stream().map(item -> item.getId()).filter(Objects::nonNull).distinct()
                    .collect(Collectors.toList()));
        }
        lineage.setDownstreamMetricIds(domainMetrics.stream()
                .filter(item -> MetricDefineType.METRIC.equals(item.getMetricDefineType())
                        && item.getMetricDefineByMetricParams() != null
                        && item.getMetricDefineByMetricParams().getMetrics() != null
                        && item.getMetricDefineByMetricParams().getMetrics().stream()
                                .anyMatch(param -> metricId.equals(param.getId())))
                .map(MetricResp::getId).distinct().collect(Collectors.toList()));
        lineage.setRelatedDimensionIds(
                metric.getDrillDownDimensions() == null ? Collections.emptyList()
                        : metric.getDrillDownDimensions().stream()
                                .map(item -> item.getDimensionId()).distinct()
                                .collect(Collectors.toList()));
        List<DataSetResp> dataSets = dataSetService.getDataSetList(model.getDomainId(),
                Arrays.asList(StatusEnum.ONLINE.getCode(), StatusEnum.OFFLINE.getCode()));
        lineage.setReferencedDataSetIds(dataSets.stream()
                .filter(item -> item.metricIds().contains(metricId)
                        || item.getAllIncludeAllModels().contains(metric.getModelId()))
                .map(DataSetResp::getId).collect(Collectors.toList()));
        lineage.setOrganizationMappings(governanceService.listMappings(metricId));
        return lineage;
    }
}
