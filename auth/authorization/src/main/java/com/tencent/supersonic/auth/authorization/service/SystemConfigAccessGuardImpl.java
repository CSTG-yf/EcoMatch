package com.tencent.supersonic.auth.authorization.service;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.SystemConfigAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

@Component
public class SystemConfigAccessGuardImpl implements SystemConfigAccessGuard {

    private final UserService userService;

    public SystemConfigAccessGuardImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void requireAdministrator(HttpServletRequest request, HttpServletResponse response) {
        User user = userService.getCurrentUser(request, response);
        if (user == null || !user.isSuperAdmin()) {
            throw new InvalidPermissionException(
                    "Only super administrators can manage system configuration");
        }
    }
}
