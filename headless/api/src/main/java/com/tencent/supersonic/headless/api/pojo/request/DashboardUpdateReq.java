package com.tencent.supersonic.headless.api.pojo.request;

import com.tencent.supersonic.headless.api.pojo.enums.DashboardAccessScope;
import lombok.Data;

@Data
public class DashboardUpdateReq {

    private Integer version;

    private String name;

    private String description;

    private DashboardAccessScope accessScope;

    private String config;
}
