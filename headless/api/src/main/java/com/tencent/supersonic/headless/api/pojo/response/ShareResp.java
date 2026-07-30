package com.tencent.supersonic.headless.api.pojo.response;

import com.tencent.supersonic.headless.api.pojo.enums.ShareIdentityPolicy;
import com.tencent.supersonic.headless.api.pojo.enums.ShareStatus;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class ShareResp {

    private String shareId;

    private Long dashboardId;

    private String dashboardName;

    private ShareIdentityPolicy identityPolicy;

    private List<String> allowedUsers;

    private ShareStatus status;

    private Integer maxAccessCount;

    private Integer accessCount;

    private boolean watermarkEnabled;

    private Date expiresAt;

    private Date createdAt;

    private Date revokedAt;

    /** Present only in the create response. The server never persists the raw token. */
    private String token;
}
