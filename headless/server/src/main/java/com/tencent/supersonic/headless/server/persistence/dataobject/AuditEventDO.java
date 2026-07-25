package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_audit_event")
public class AuditEventDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String traceId;
    private Long chatId;
    private Long queryId;
    private String userName;
    private String organizationId;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String outcome;
    private String reasonCode;
    private String sanitizedQuestion;
    private String questionHash;
    private String metricCodes;
    private String sqlType;
    private String sqlDigest;
    private String policyIds;
    private String maskingSummary;
    private Long exportRowCount;
    private String fileType;
    private Long fileSize;
    private String clientIpHash;
    private String userAgentHash;
    private Long durationMs;
    private String metadataJson;
    private Date eventTime;
    private String previousHash;
    private String eventHash;
    private Date createdAt;
}
