package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.server.service.BusinessInsightService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessInsightControllerSecurityTest {

    @Test
    void rejectsVisitorBeforeRecommendationOrExplanationRuns() {
        BusinessInsightService service = mock(BusinessInsightService.class);
        BusinessInsightController controller = new BusinessInsightController(service);
        BusinessInsightReq request = new BusinessInsightReq();
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);

        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(servletRequest, servletResponse))
                    .thenReturn(User.getVisitUser());

            assertThrows(InvalidPermissionException.class,
                    () -> controller.recommend(request, servletRequest, servletResponse));
            assertThrows(InvalidPermissionException.class,
                    () -> controller.explain(request, servletRequest, servletResponse));
        }

        verify(service, never()).recommend(request);
        verify(service, never()).explain(request);
    }

    @Test
    void allowsAuthenticatedUserToInvokeBothEndpoints() {
        BusinessInsightService service = mock(BusinessInsightService.class);
        BusinessInsightController controller = new BusinessInsightController(service);
        BusinessInsightReq request = new BusinessInsightReq();
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(service.recommend(request)).thenReturn(null);
        when(service.explain(request)).thenReturn(null);

        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(servletRequest, servletResponse))
                    .thenReturn(User.get(2L, "analyst"));

            controller.recommend(request, servletRequest, servletResponse);
            controller.explain(request, servletRequest, servletResponse);
        }

        verify(service).recommend(request);
        verify(service).explain(request);
    }
}
