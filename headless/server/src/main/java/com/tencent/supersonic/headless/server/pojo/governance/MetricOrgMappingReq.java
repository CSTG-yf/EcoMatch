package com.tencent.supersonic.headless.server.pojo.governance;

import lombok.Data;

import java.util.Date;

@Data
public class MetricOrgMappingReq {

    private Long id;
    private Long metricId;
    private String organizationCode;
    private String externalMetricCode;
    private String externalMetricName;
    private String businessDefinition;
    private String mappingStatus = "ACTIVE";
    private Date effectiveFrom;
    private Date effectiveTo;
}
