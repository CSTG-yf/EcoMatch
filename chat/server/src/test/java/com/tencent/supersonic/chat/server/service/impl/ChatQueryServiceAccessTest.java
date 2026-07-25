package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatQueryServiceAccessTest {

    @Test
    void checksObjectAccessBeforeReadingSummaryCacheOrHistory() {
        ChatManageService chatManageService = mock(ChatManageService.class);
        ChatQueryServiceImpl service = new ChatQueryServiceImpl();
        ReflectionTestUtils.setField(service, "chatManageService", chatManageService);
        User user = User.get(2L, "analyst");
        ChatExecuteReq request =
                ChatExecuteReq.builder().queryId(987654321L).user(user).build();
        doThrow(new InvalidPermissionException("Query access denied")).when(chatManageService)
                .checkQueryAccess(request.getQueryId(), user);

        assertThrows(InvalidPermissionException.class, () -> service.getTextSummary(request));

        verify(chatManageService).checkQueryAccess(request.getQueryId(), user);
        verify(chatManageService, never()).getChatQueryDO(request.getQueryId());
    }

    @Test
    void checksObjectAccessBeforeReadingParseForSecondaryQuery() {
        ChatManageService chatManageService = mock(ChatManageService.class);
        ChatQueryServiceImpl service = new ChatQueryServiceImpl();
        ReflectionTestUtils.setField(service, "chatManageService", chatManageService);
        User user = User.get(3L, "analyst");
        ChatQueryDataReq request = new ChatQueryDataReq();
        request.setQueryId(123456789L);
        request.setParseId(1);
        doThrow(new InvalidPermissionException("Query access denied")).when(chatManageService)
                .checkQueryAccess(request.getQueryId(), user);

        assertThrows(InvalidPermissionException.class, () -> service.queryData(request, user));

        verify(chatManageService).checkQueryAccess(request.getQueryId(), user);
        verify(chatManageService, never()).getParseInfo(request.getQueryId(), request.getParseId());
    }

    @Test
    void rejectsAgentThatAuthenticatedUserCannotView() {
        AgentService agentService = mock(AgentService.class);
        ChatQueryServiceImpl service = new ChatQueryServiceImpl();
        ReflectionTestUtils.setField(service, "agentService", agentService);
        User user = User.get(4L, "analyst");
        ChatParseReq request =
                ChatParseReq.builder().agentId(99).queryText("贷款余额").user(user).build();
        when(agentService.getAgents(user, AuthType.VIEWER)).thenReturn(java.util.List.of());

        assertThrows(InvalidPermissionException.class, () -> service.search(request));

        verify(agentService).getAgents(user, AuthType.VIEWER);
    }
}
