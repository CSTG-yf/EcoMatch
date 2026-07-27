package com.tencent.supersonic.headless.server.facade.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatQueryApiControllerSecurityTest {

    @Test
    void combinedQueryOverwritesClientSuppliedUserAndDoesNotEchoQuestion() throws Exception {
        ChatLayerService chatLayerService = mock(ChatLayerService.class);
        ChatQueryApiController controller = new ChatQueryApiController();
        ReflectionTestUtils.setField(controller, "chatLayerService", chatLayerService);
        ReflectionTestUtils.setField(controller, "semanticLayerService",
                mock(SemanticLayerService.class));

        QueryNLReq requestBody = new QueryNLReq();
        requestBody.setUser(User.get(99L, "spoofed-user"));
        requestBody.setQueryText("customer secret question");
        ParseResp parseResp = new ParseResp(requestBody.getQueryText());
        parseResp.setState(ParseResp.ParseState.FAILED);
        when(chatLayerService.parse(requestBody)).thenReturn(parseResp);

        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        User authenticatedUser = User.get(1L, "authenticated-user");
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(servletRequest, servletResponse))
                    .thenReturn(authenticatedUser);

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> controller.queryByNL(requestBody, servletRequest, servletResponse));

            assertEquals("Failed to parse natural language query", error.getMessage());
        }

        ArgumentCaptor<QueryNLReq> requestCaptor = ArgumentCaptor.forClass(QueryNLReq.class);
        verify(chatLayerService).parse(requestCaptor.capture());
        assertSame(authenticatedUser, requestCaptor.getValue().getUser());
    }
}
