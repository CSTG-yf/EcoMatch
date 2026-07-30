package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_dashboard")
public class DashboardDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long domainId;

    private String name;

    private String description;

    private String status;

    private String accessScope;

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
