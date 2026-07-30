package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.ShareCreateReq;
import com.tencent.supersonic.headless.api.pojo.response.ShareAccessResp;
import com.tencent.supersonic.headless.api.pojo.response.ShareResp;
import com.tencent.supersonic.headless.server.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic/share")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @PostMapping
    public ShareResp create(@RequestBody ShareCreateReq createReq, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return shareService.create(createReq, user(request, response));
    }

    @GetMapping
    public PageInfo<ShareResp> list(
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request, HttpServletResponse response) {
        return shareService.list(pageNum, pageSize, user(request, response));
    }

    @GetMapping("/{shareId}")
    public ShareResp get(@PathVariable("shareId") String shareId, HttpServletRequest request,
            HttpServletResponse response) {
        return shareService.get(shareId, user(request, response));
    }

    @DeleteMapping("/{shareId}")
    public void revoke(@PathVariable("shareId") String shareId, HttpServletRequest request,
            HttpServletResponse response) {
        shareService.revoke(shareId, user(request, response));
    }

    @GetMapping("/access/{token}")
    public ShareAccessResp access(@PathVariable("token") String token, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return shareService.access(token, user(request, response));
    }

    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
