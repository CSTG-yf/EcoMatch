package com.tencent.supersonic.headless.api.pojo.response;

import lombok.Data;

import java.util.Date;

@Data
public class ShareAccessResp {

    private String shareId;

    private DashboardResp dashboard;

    private String watermarkUser;

    private String watermarkOrganization;

    private Date accessedAt;
}
