package com.tencent.supersonic.headless.server.service;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.ShareCreateReq;
import com.tencent.supersonic.headless.api.pojo.response.ShareAccessResp;
import com.tencent.supersonic.headless.api.pojo.response.ShareResp;

public interface ShareService {

    ShareResp create(ShareCreateReq request, User user);

    PageInfo<ShareResp> list(int pageNum, int pageSize, User user);

    ShareResp get(String shareId, User user);

    void revoke(String shareId, User user);

    ShareAccessResp access(String token, User user);
}
