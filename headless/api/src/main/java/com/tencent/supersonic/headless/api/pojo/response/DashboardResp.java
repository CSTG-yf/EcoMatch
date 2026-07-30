package com.tencent.supersonic.headless.api.pojo.response;

import com.tencent.supersonic.headless.api.pojo.enums.DashboardAccessScope;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import lombok.Data;

import java.util.Date;

@Data
public class DashboardResp {

    private Long id;

    private Long domainId;

    private String name;

    private String description;

    private DashboardStatus status;

    private DashboardAccessScope accessScope;

    private String owner;

    private String organizationId;

    private String config;

    private Integer version;

    private Date publishedAt;

    private Date disabledAt;

    private Date createdAt;

    private String createdBy;

    private Date updatedAt;

    private String updatedBy;
}
