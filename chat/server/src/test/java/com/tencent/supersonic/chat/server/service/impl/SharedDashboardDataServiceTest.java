package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.SharedDashboardDataReq;
import com.tencent.supersonic.chat.api.pojo.response.SharedDashboardDataResp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.ShareAccessResp;
import com.tencent.supersonic.headless.server.service.ShareService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedDashboardDataServiceTest {

    private final ShareService shareService = mock(ShareService.class);
    private final DashboardQueryService dashboardQueryService = mock(DashboardQueryService.class);
    private final SharedDashboardDataService service =
            new SharedDashboardDataService(shareService, dashboardQueryService);

    @Test
    void authorizesOnceAndExecutesAllComponentsAsViewer() {
        User viewer = User.get(8L, "viewer");
        DashboardResp dashboard = new DashboardResp();
        dashboard.setId(17L);
        ShareAccessResp access = new ShareAccessResp();
        access.setShareId("share-1");
        access.setDashboard(dashboard);
        access.setWatermarkUser("Viewer");
        access.setWatermarkOrganization("ORG-1");
        access.setAccessedAt(new Date(1_000L));
        when(shareService.access("secret-token", viewer)).thenReturn(access);
        when(dashboardQueryService.queryAll(dashboard, viewer)).thenReturn(
                new DashboardQueryService.BatchQueryResult(Map.of("component-1", "result"),
                        Map.of("component-2", "FORBIDDEN")));

        SharedDashboardDataReq request = new SharedDashboardDataReq();
        request.setToken("secret-token");
        SharedDashboardDataResp response = service.query(request, viewer);

        assertEquals("share-1", response.getShareId());
        assertEquals("result", response.getComponentData().get("component-1"));
        assertEquals("FORBIDDEN", response.getComponentErrors().get("component-2"));
        assertEquals("Viewer", response.getWatermarkUser());
        verify(shareService).access("secret-token", viewer);
        verify(dashboardQueryService).queryAll(dashboard, viewer);
    }

    @Test
    void rejectsMissingTokenBeforeCallingShareService() {
        assertThrows(InvalidArgumentException.class,
                () -> service.query(new SharedDashboardDataReq(), User.get(8L, "viewer")));
    }
}
