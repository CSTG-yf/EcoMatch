package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_metric_version")
public class MetricVersionDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private Integer versionNo;
    private String snapshotJson;
    private String changeSummary;
    private String approvalStatus;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Date createdAt;
    private String createdBy;
}
