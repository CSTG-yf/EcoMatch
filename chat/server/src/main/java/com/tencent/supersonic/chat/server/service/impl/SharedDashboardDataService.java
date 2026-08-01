package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.SharedDashboardDataReq;
import com.tencent.supersonic.chat.api.pojo.response.SharedDashboardDataResp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.response.ShareAccessResp;
import com.tencent.supersonic.headless.server.service.ShareService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class SharedDashboardDataService {

    private final ShareService shareService;
    private final DashboardQueryService dashboardQueryService;

    public SharedDashboardDataService(ShareService shareService,
            DashboardQueryService dashboardQueryService) {
        this.shareService = shareService;
        this.dashboardQueryService = dashboardQueryService;
    }

    public SharedDashboardDataResp query(SharedDashboardDataReq request, User user) {
        if (request == null || StringUtils.isBlank(request.getToken())) {
            throw new InvalidArgumentException("share token is required");
        }
        ShareAccessResp access = shareService.access(request.getToken(), user);
        DashboardQueryService.BatchQueryResult batch =
                dashboardQueryService.queryAll(access.getDashboard(), user);
        SharedDashboardDataResp response = new SharedDashboardDataResp();
        response.setShareId(access.getShareId());
        response.setDashboard(access.getDashboard());
        response.setWatermarkUser(access.getWatermarkUser());
        response.setWatermarkOrganization(access.getWatermarkOrganization());
        response.setAccessedAt(access.getAccessedAt());
        response.setComponentData(batch.data());
        response.setComponentErrors(batch.errors());
        return response;
    }
}
