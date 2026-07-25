package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.persistence.repository.ChatRepository;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatManageServiceAccessTest {

    @Test
    void rejectsCrossUserHistoryBeforeReadingQueries() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository);
        ChatDO chat = chat(10L, "alice");
        when(chatRepository.getChat(chat.getChatId())).thenReturn(chat);

        assertThrows(InvalidPermissionException.class,
                () -> service.getChatQueries(10, User.get(3L, "bob")));
        ChatParseReq createRequest =
                ChatParseReq.builder().chatId(10).user(User.get(3L, "bob")).build();
        assertThrows(InvalidPermissionException.class,
                () -> service.createChatQuery(createRequest));
        assertThrows(InvalidPermissionException.class,
                () -> service.getChatQueries(-1, User.get(3L, "bob")));

        verify(queryRepository, never()).getChatQueries(anyInt());
        verify(queryRepository, never()).createChatQuery(any());
    }

    @Test
    void savesResultAgainstPersistedChatInsteadOfRequestChat() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository);
        User user = User.get(2L, "alice");
        ChatQueryDO persisted = new ChatQueryDO();
        persisted.setQuestionId(20L);
        persisted.setChatId(10L);
        persisted.setUserName("alice");
        persisted.setQueryText("trusted question");
        when(queryRepository.getChatQueryDO(20L)).thenReturn(persisted);
        when(chatRepository.getChat(10L)).thenReturn(chat(10L, "alice"));
        ChatExecuteReq request = ChatExecuteReq.builder().queryId(20L).chatId(99)
                .queryText("untrusted question").user(user).build();

        service.saveQueryResult(request, new QueryResult());

        verify(chatRepository).updateLastQuestion(eq(10L), eq("trusted question"), any());
        verify(chatRepository, never()).updateLastQuestion(eq(99L), any(), any());
    }

    private ChatManageServiceImpl service(ChatRepository chatRepository,
            ChatQueryRepository queryRepository) {
        ChatManageServiceImpl service = new ChatManageServiceImpl();
        ReflectionTestUtils.setField(service, "chatRepository", chatRepository);
        ReflectionTestUtils.setField(service, "chatQueryRepository", queryRepository);
        return service;
    }

    private ChatDO chat(Long chatId, String owner) {
        ChatDO chat = new ChatDO();
        chat.setChatId(chatId);
        chat.setCreator(owner);
        return chat;
    }
}
