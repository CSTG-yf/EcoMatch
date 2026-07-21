package com.tencent.supersonic.headless.server.pojo.governance;

import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricApprovalDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricGovernanceDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricOrgMappingDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricVersionDO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetricGovernanceDetail {

    private MetricResp metric;
    private MetricGovernanceDO governance;
    private List<MetricVersionDO> versions = new ArrayList<>();
    private List<MetricApprovalDO> approvals = new ArrayList<>();
    private List<MetricOrgMappingDO> organizationMappings = new ArrayList<>();
}
