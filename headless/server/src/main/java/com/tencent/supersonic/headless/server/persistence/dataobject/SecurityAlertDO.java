package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_security_alert")
public class SecurityAlertDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String alertId;
    private String fingerprint;
    private Long ruleId;
    private String ruleCode;
    private String traceId;
    private String userName;
    private String organizationId;
    private String resourceType;
    private String resourceId;
    private String severity;
    private String status;
    private String title;
    private String description;
    private String evidenceIds;
    private Long occurrenceCount;
    private Date firstSeen;
    private Date lastSeen;
    @Version
    private Integer version;
    private Date createdAt;
    private String createdBy;
    private Date updatedAt;
    private String updatedBy;
}
