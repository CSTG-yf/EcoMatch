package com.tencent.supersonic.auth.authorization.rest;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.auth.api.authorization.audit.AuthorizationAuditSink;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private AuthorizationAuditSink auditSink;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @Autowired(required = false)
    public void setAuditSink(AuthorizationAuditSink auditSink) {
        this.auditSink = auditSink;
    }

    @GetMapping("/queryGroup")
    public List<AuthGroup> queryAuthGroup(@RequestParam("modelId") String modelId,
            @RequestParam(value = "groupId", required = false) Integer groupId,
            HttpServletRequest request, HttpServletResponse response) {
        requireSuperAdmin(request, response);
        List<AuthGroup> groups = authService.queryAuthGroups(modelId, groupId);
        User user = userService.getCurrentUser(request, response);
        groups.forEach(group -> audit("POLICY_PREVIEWED", group, user));
        return groups;
    }

    /** 新建权限组 */
    @PostMapping("/createGroup")
    public void newAuthGroup(@RequestBody AuthGroup group, HttpServletRequest request,
            HttpServletResponse response) {
        requireSuperAdmin(request, response);
        group.setGroupId(null);
        authService.addOrUpdateAuthGroup(group);
        audit("POLICY_CREATED", group, currentUser(request, response));
    }

    @PostMapping("/removeGroup")
    public void removeAuthGroup(@RequestBody AuthGroup group, HttpServletRequest request,
            HttpServletResponse response) {
        requireSuperAdmin(request, response);
        authService.removeAuthGroup(group);
        audit("POLICY_DISABLED", group, currentUser(request, response));
    }

    /**
     * 更新权限组
     *
     * @param group
     */
    @PostMapping("/updateGroup")
    public void updateAuthGroup(@RequestBody AuthGroup group, HttpServletRequest request,
            HttpServletResponse response) {
        requireSuperAdmin(request, response);
        if (group.getGroupId() == null || group.getGroupId() == 0) {
            throw new RuntimeException("groupId is empty");
        }
        authService.addOrUpdateAuthGroup(group);
        audit("POLICY_UPDATED", group, currentUser(request, response));
    }

    /**
     * 查询有权限访问的受限资源id
     *
     * @param req
     * @return
     */
    @PostMapping("/queryAuthorizedRes")
    public AuthorizedResourceResp queryAuthorizedResources(@RequestBody QueryAuthResReq req,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        AuthorizedResourceResp result = authService.queryAuthorizedResources(req, user);
        if (req.getModelIds() != null && !req.getModelIds().isEmpty()) {
            audit("POLICY_PREVIEWED", req.getModelIds().get(0), null, result.getPolicyVersion(), user);
        }
        return result;
    }

    private User currentUser(HttpServletRequest request, HttpServletResponse response) {
        return userService.getCurrentUser(request, response);
    }

    private void audit(String eventType, AuthGroup group, User user) {
        if (group != null) {
            audit(eventType, group.getModelId(), group.getGroupId(), group.getPolicyVersion(), user);
        }
    }

    private void audit(String eventType, Long modelId, Integer groupId, Long policyVersion,
            User user) {
        if (auditSink != null) {
            auditSink.publish(eventType, modelId, groupId, policyVersion, user);
        }
    }

    private void requireSuperAdmin(HttpServletRequest request, HttpServletResponse response) {
        User user = userService.getCurrentUser(request, response);
        if (user == null || !user.isSuperAdmin()) {
            throw new InvalidPermissionException(
                    "Only super administrators can manage authorization groups");
        }
    }
}
