package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChartFeedbackReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.PageQueryInfoReq;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.config.BankPlanSessionWarmupCoordinator;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.persistence.repository.ChatRepository;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
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
    void schedulesPrefixWarmupAfterCreatingANewChat() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AgentService agentService = mock(AgentService.class);
        BankPlanSessionWarmupCoordinator warmup = mock(BankPlanSessionWarmupCoordinator.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, agentService,
                mock(AuditEventPublisher.class));
        ReflectionTestUtils.setField(service, "bankPlanSessionWarmupCoordinator", warmup);
        User user = User.get(2L, "alice");
        Agent agent = agent(7, "贷款助理", 1);
        when(agentService.getAgents(user, AuthType.VIEWER)).thenReturn(java.util.List.of(agent));
        when(chatRepository.createChat(any())).thenReturn(42L);

        assertEquals(42L, service.addChat(user, null, 7));

        verify(warmup).warmAsync(42L, agent);
    }

    @Test
    void createsAgentBoundChatsWithNormalizedTitles() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AgentService agentService = mock(AgentService.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, agentService,
                mock(AuditEventPublisher.class));
        User user = User.get(2L, "alice");
        Agent agent = agent(7, "贷款助理", 1);
        when(agentService.getAgents(user, AuthType.VIEWER)).thenReturn(java.util.List.of(agent));

        service.addChat(user, null, 7);
        service.addChat(user, "新问答对话", 7);
        service.addChat(user, "季度分析", 7);

        ArgumentCaptor<ChatDO> chats = ArgumentCaptor.forClass(ChatDO.class);
        verify(chatRepository, org.mockito.Mockito.times(3)).createChat(chats.capture());
        assertEquals(java.util.List.of("贷款助理", "贷款助理", "季度分析"),
                chats.getAllValues().stream().map(ChatDO::getChatName).toList());
        chats.getAllValues().forEach(chat -> {
            assertEquals("alice", chat.getCreator());
            assertEquals(7, chat.getAgentId());
        });
    }

    @Test
    void rejectsUnauthorizedOrOfflineAgentBeforeCreatingChat() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        AgentService agentService = mock(AgentService.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository, agentService,
                mock(AuditEventPublisher.class));
        User user = User.get(2L, "alice");
        when(agentService.getAgents(user, AuthType.VIEWER)).thenReturn(java.util.List.of());

        assertThrows(InvalidPermissionException.class, () -> service.addChat(user, "季度分析", 7));

        when(agentService.getAgents(user, AuthType.VIEWER))
                .thenReturn(java.util.List.of(agent(7, "贷款助理", 0)));
        assertThrows(InvalidPermissionException.class, () -> service.addChat(user, "季度分析", 7));
        assertThrows(InvalidPermissionException.class, () -> service.addChat(null, "季度分析", 7));
        verify(chatRepository, never()).createChat(any());
    }

    @Test
    void rejectsQueryAgentMismatchBeforeRepositoryWrite() {
        ChatRepository chatRepository = mock(ChatRepository.class);
        ChatQueryRepository queryRepository = mock(ChatQueryRepository.class);
        ChatManageServiceImpl service = service(chatRepository, queryRepository);
        ChatDO chat = chat(10L, "alice");
        chat.setAgentId(7);
        when(chatRepository.getChat(10L)).thenReturn(chat);
        ChatParseReq request =
                ChatParseReq.builder().chatId(10).agentId(8).user(User.get(2L, "alice")).build();

        assertThrows(InvalidPermissionException.class, () -> service.createChatQuery(request));

        verify(queryRepository, never()).createChatQuery(any());
    }

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
        return service(chatRepository, queryRepository, mock(AgentService.class), publisher);
    }

    private ChatManageServiceImpl service(ChatRepository chatRepository,
            ChatQueryRepository queryRepository, AgentService agentService,
            AuditEventPublisher publisher) {
        ChatManageServiceImpl service = new ChatManageServiceImpl();
        ReflectionTestUtils.setField(service, "chatRepository", chatRepository);
        ReflectionTestUtils.setField(service, "chatQueryRepository", queryRepository);
        ReflectionTestUtils.setField(service, "agentService", agentService);
        ReflectionTestUtils.setField(service, "auditEventPublisher", publisher);
        return service;
    }

    private ChatDO chat(Long chatId, String owner) {
        ChatDO chat = new ChatDO();
        chat.setChatId(chatId);
        chat.setCreator(owner);
        return chat;
    }

    private Agent agent(Integer id, String name, Integer status) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        agent.setStatus(status);
        return agent;
    }
}
