package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardVersionReq;
import com.tencent.supersonic.headless.server.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class DashboardControllerTest {

    private final DashboardService service = mock(DashboardService.class);
    private final DashboardController controller = new DashboardController(service);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final User user = User.get(2L, "owner");

    @Test
    void forwardsAuthenticatedIdentityToMutations() {
        DashboardCreateReq create = new DashboardCreateReq();
        DashboardVersionReq version = new DashboardVersionReq();
        version.setVersion(3);

        try (MockedStatic<UserHolder> holder = mockStatic(UserHolder.class)) {
            holder.when(() -> UserHolder.findUser(request, response)).thenReturn(user);
            controller.list(10L, DashboardStatus.PUBLISHED, 2, 25, request, response);
            controller.create(create, request, response);
            controller.publish(1L, version, request, response);
            controller.disable(1L, version, request, response);
            controller.delete(1L, request, response);
        }

        verify(service).list(10L, DashboardStatus.PUBLISHED, 2, 25, user);
        verify(service).create(create, user);
        verify(service).publish(1L, 3, user);
        verify(service).disable(1L, 3, user);
        verify(service).delete(1L, user);
    }
}
