package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.enums.ShareIdentityPolicy;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class ShareCreateReq {

    private Long dashboardId;

    private ShareIdentityPolicy identityPolicy = ShareIdentityPolicy.AUTHENTICATED;

    private List<String> allowedUsers = new ArrayList<>();

    private Date expiresAt;

    private Integer maxAccessCount;

    private boolean watermarkEnabled = true;
}
