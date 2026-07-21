package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_metric_approval")
public class MetricApprovalDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private Long versionId;
    private String action;
    private String approvalStatus;
    private String commentText;
    private Date createdAt;
    private String createdBy;
    private Date decidedAt;
    private String decidedBy;
}
