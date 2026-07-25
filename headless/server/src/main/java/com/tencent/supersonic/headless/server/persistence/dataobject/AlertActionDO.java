package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_alert_action")
public class AlertActionDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String actionId;
    private String alertId;
    private String fromStatus;
    private String toStatus;
    private String action;
    private String operatorName;
    private String comment;
    private Date createdAt;
}
