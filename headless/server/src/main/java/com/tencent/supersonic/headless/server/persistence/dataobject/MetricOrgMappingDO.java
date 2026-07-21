package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_metric_org_mapping")
public class MetricOrgMappingDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private String organizationCode;
    private String externalMetricCode;
    private String externalMetricName;
    private String businessDefinition;
    private String mappingStatus;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Date createdAt;
    private String createdBy;
    private Date updatedAt;
    private String updatedBy;
}
