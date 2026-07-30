package com.tencent.supersonic.headless.server.service;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCopyReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardUpdateReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;

public interface DashboardService {

    PageInfo<DashboardResp> list(Long domainId, DashboardStatus status, int pageNum, int pageSize,
            User user);

    DashboardResp get(Long id, User user);

    DashboardResp create(DashboardCreateReq request, User user);

    DashboardResp update(Long id, DashboardUpdateReq request, User user);

    DashboardResp copy(Long id, DashboardCopyReq request, User user);

    DashboardResp publish(Long id, Integer version, User user);

    DashboardResp disable(Long id, Integer version, User user);

    void delete(Long id, User user);
}
