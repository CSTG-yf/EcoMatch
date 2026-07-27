package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_audit_rule")
public class AuditRuleDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private Long thresholdValue;
    private Long windowSeconds;
    private String workHoursStart;
    private String workHoursEnd;
    private String severity;
    private Boolean enabled;
    private String configJson;
    private Date createdAt;
    private String createdBy;
    private Date updatedAt;
    private String updatedBy;
    @Version
    private Integer version;
}
