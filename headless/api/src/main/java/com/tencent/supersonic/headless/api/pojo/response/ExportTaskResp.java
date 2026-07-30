package com.tencent.supersonic.headless.api.pojo.response;

import com.tencent.supersonic.headless.api.pojo.enums.ExportFormat;
import com.tencent.supersonic.headless.api.pojo.enums.ExportResourceType;
import com.tencent.supersonic.headless.api.pojo.enums.ExportStatus;
import lombok.Data;

import java.util.Date;

@Data
public class ExportTaskResp {

    private String taskId;

    private ExportResourceType resourceType;

    private String resourceId;

    private ExportFormat format;

    private ExportStatus status;

    private String fileName;

    private Long fileSize;

    private Long rowCount;

    private String maskingSummary;

    private String failureCode;

    private Date expiresAt;

    private Date createdAt;

    private Date completedAt;

    private boolean downloadable;
}
