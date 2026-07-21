package com.tencent.supersonic.headless.server.pojo.governance;

import lombok.Data;

import java.util.Date;

@Data
public class MetricGovernanceReq {

    private String ownerDepartment;
    private String sourceSystem;
    private String businessDefinition;
    private Date effectiveFrom;
    private Date effectiveTo;
    private String changeSummary;
}
