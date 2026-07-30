package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.enums.DashboardAccessScope;
import lombok.Data;

@Data
public class DashboardCreateReq {

    private Long domainId;

    private String name;

    private String description;

    private DashboardAccessScope accessScope = DashboardAccessScope.PRIVATE;

    private String config;
}
