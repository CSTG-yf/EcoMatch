package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("s2_share")
public class ShareDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shareId;

    private String tokenHash;

    private Long dashboardId;

    private String owner;

    private String organizationId;

    private String identityPolicy;

    private String allowedUsers;

    private String status;

    private Integer maxAccessCount;

    private Integer accessCount;

    private Boolean watermarkEnabled;

    private Date expiresAt;

    private Date createdAt;

    private Date updatedAt;

    private Date revokedAt;
}
