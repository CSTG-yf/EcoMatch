package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChartFeedbackReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.PageQueryInfoReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.persistence.repository.ChatRepository;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void scopesShowCasesToAuthenticatedUser() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository);
        PageQueryInfoReq request = new PageQueryInfoReq();
        request.setCurrent(1);
        request.setPageSize(10);
        request.setUserName("mallory");
        when(queryRepository.queryShowCase(request, 7)).thenReturn(java.util.List.of());

        service.queryShowCase(request, 7, User.get(2L, "alice"));

        org.junit.jupiter.api.Assertions.assertEquals("alice", request.getUserName());
        verify(queryRepository).queryShowCase(request, 7);
        assertThrows(InvalidPermissionException.class,
                () -> service.queryShowCase(new PageQueryInfoReq(), 7, null));
    }

    @Test
    void auditsDeniedChatAccessAsRequired() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, publisher);
        User user = User.get(3L, "bob");
        when(chatRepository.getChat(10L)).thenReturn(chat(10L, "alice"));

        assertThrows(InvalidPermissionException.class, () -> service.checkChatAccess(10L, user));

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishRequired(eventCaptor.capture(), eq(user));
        AuditEvent event = eventCaptor.getValue();
        assertEquals(AuditEventType.OBJECT_ACCESS_DENIED, event.getEventType());
        assertEquals(AuditOutcome.DENIED, event.getOutcome());
        assertEquals("CHAT_OWNERSHIP_DENIED", event.getReasonCode());
        assertEquals(10L, event.getChatId());
        assertEquals("CHAT", event.getResourceType());
    }

    @Test
    void auditsAllowedChatAccessAsBestEffort() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, publisher);
        User user = User.get(2L, "alice");
        when(chatRepository.getChat(10L)).thenReturn(chat(10L, "alice"));

        service.checkChatAccess(10L, user);

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishBestEffort(eventCaptor.capture(), eq(user));
        AuditEvent event = eventCaptor.getValue();
        assertEquals(AuditEventType.OBJECT_ACCESS_ALLOWED, event.getEventType());
        assertEquals(AuditOutcome.SUCCESS, event.getOutcome());
        assertEquals("CHAT_ACCESS_ALLOWED", event.getReasonCode());
        assertEquals(10L, event.getChatId());
        verify(publisher, never()).publishRequired(any(), eq(user));
    }

    @Test
    void recordsAuthorizedChartSelectionAsRequiredAudit() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, publisher);
        User user = User.get(2L, "alice");
        ChatQueryDO query = new ChatQueryDO();
        query.setQuestionId(20L);
        query.setUserName("alice");
        when(queryRepository.getChatQueryDO(20L)).thenReturn(query);
        ChartFeedbackReq request = new ChartFeedbackReq();
        request.setQueryId(20L);
        request.setRecommendedChart("BAR");
        request.setSelectedChart("TABLE");
        request.setSource("CHART_SELECTOR");

        service.recordChartFeedback(request, user);

        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher).publishRequired(events.capture(), eq(user));
        AuditEvent feedback = events.getValue();
        assertEquals(AuditEventType.CHART_VISUALIZATION_CHANGED, feedback.getEventType());
        assertEquals("BAR", feedback.getMetadata().get("recommendedChart"));
        assertEquals("TABLE", feedback.getMetadata().get("selectedChart"));
    }

    @Test
    void rejectsUnsupportedChartFeedbackBeforeAudit() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, publisher);
        ChartFeedbackReq request = new ChartFeedbackReq();
        request.setQueryId(20L);
        request.setRecommendedChart("BAR");
        request.setSelectedChart("SCRIPT");
        request.setSource("CHART_SELECTOR");

        assertThrows(IllegalArgumentException.class,
                () -> service.recordChartFeedback(request, User.get(2L, "alice")));
        verify(queryRepository, never()).getChatQueryDO(any());
        verify(publisher, never()).publishRequired(any(), any());
    }

    private ChatManageServiceImpl service(ChatRepository chatRepository,
            ChatQueryRepository queryRepository) {
        return service(chatRepository, queryRepository, mock(AuditEventPublisher.class));
    }

    private ChatManageServiceImpl service(ChatRepository chatRepository,
            ChatQueryRepository queryRepository, AuditEventPublisher publisher) {
        ChatManageServiceImpl service = new ChatManageServiceImpl();
        ReflectionTestUtils.setField(service, "chatRepository", chatRepository);
        ReflectionTestUtils.setField(service, "chatQueryRepository", queryRepository);
        ReflectionTestUtils.setField(service, "auditEventPublisher", publisher);
        return service;
    }

    private ChatDO chat(Long chatId, String owner) {
        ChatDO chat = new ChatDO();
        chat.setChatId(chatId);
        chat.setCreator(owner);
        return chat;
    }
}
