package com.tencent.supersonic.headless.server.pojo.governance;

import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetricLineage {

    private Long metricId;
    private Long modelId;
    private List<Long> upstreamMetricIds = new ArrayList<>();
    private List<Long> downstreamMetricIds = new ArrayList<>();
    private List<Long> relatedDimensionIds = new ArrayList<>();
    private List<Long> referencedDataSetIds = new ArrayList<>();
    private List<MetricOrgMappingDO> organizationMappings = new ArrayList<>();
}
