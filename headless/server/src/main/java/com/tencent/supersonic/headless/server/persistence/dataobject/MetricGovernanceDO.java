package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_metric_governance")
public class MetricGovernanceDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private Integer currentVersion;
    private String governanceStatus;
    private String ownerDepartment;
    private String sourceSystem;
    private String businessDefinition;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Date createdAt;
    private String createdBy;
    private Date updatedAt;
    private String updatedBy;
}
