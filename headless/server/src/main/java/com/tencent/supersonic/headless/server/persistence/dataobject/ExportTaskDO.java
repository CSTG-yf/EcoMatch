package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_export_task")
public class ExportTaskDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String resourceType;

    private String resourceId;

    private String format;

    private String status;

    private String owner;

    private String organizationId;

    private String storageKey;

    private String fileName;

    private Long fileSize;

    private Long rowCount;

    private String maskingSummary;

    private String failureCode;

    private Date expiresAt;

    private Date createdAt;

    private Date completedAt;

    private Date updatedAt;
}
