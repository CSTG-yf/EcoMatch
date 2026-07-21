package com.tencent.supersonic.headless.server.pojo.governance;

import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import lombok.Data;

import java.util.Date;

@Data
public class MetricVersionSnapshot {

    private MetricResp metric;
    private String ownerDepartment;
    private String sourceSystem;
    private String businessDefinition;
    private Date effectiveFrom;
    private Date effectiveTo;
}
