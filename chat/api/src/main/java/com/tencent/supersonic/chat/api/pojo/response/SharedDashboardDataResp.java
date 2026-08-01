package com.tencent.supersonic.chat.api.pojo.response;

import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import lombok.Data;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class SharedDashboardDataResp {

    private String shareId;

    private DashboardResp dashboard;

    private String watermarkUser;

    private String watermarkOrganization;

    private Date accessedAt;

    private Map<String, Object> componentData = new LinkedHashMap<>();

    private Map<String, String> componentErrors = new LinkedHashMap<>();
}
