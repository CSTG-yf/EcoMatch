package com.tencent.supersonic.chat.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.persistence.dataobject.AgentDO;
import com.tencent.supersonic.chat.server.service.ChatQueryService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.ChatModelService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceAccessTest {

    @Test
    void openAgentIsViewableButNotManageableByUnrelatedUser() {
        AgentServiceImpl service = service();
        AgentDO stored = agent(7, "alice", true);
        doReturn(List.of(stored)).when(service).list();
        doReturn(stored).when(service).getById(7);
        User bob = User.get(3L, "bob");

        assertEquals(List.of(7),
                service.getAgents(bob, AuthType.VIEWER).stream().map(Agent::getId).toList());
        assertEquals(List.of(), service.getAgents(bob, AuthType.ADMIN));
        assertThrows(InvalidPermissionException.class,
                () -> service.updateAgent(update(7, "mallory"), bob));
        assertThrows(InvalidPermissionException.class, () -> service.deleteAgent(7, bob));

        verify(service, never()).updateById(any(AgentDO.class));
        verify(service, never()).removeById(7);
    }

    @Test
    void ownerUpdatePreservesPersistedIdentityAndCreator() {
        AgentServiceImpl service = service();
        AgentDO stored = agent(7, "alice", false);
        doReturn(stored).when(service).getById(7);
        doReturn(true).when(service).updateById(any(AgentDO.class));

        Agent updated = service.updateAgent(update(7, "mallory"), User.get(2L, "alice"));

        ArgumentCaptor<AgentDO> saved = ArgumentCaptor.forClass(AgentDO.class);
        verify(service).updateById(saved.capture());
        assertEquals(7, saved.getValue().getId());
        assertEquals("alice", saved.getValue().getCreatedBy());
        assertEquals(stored.getCreatedAt(), saved.getValue().getCreatedAt());
        assertEquals("alice", updated.getCreatedBy());
    }

    @Test
    void ownerUpdatePreservesPermissionFieldsThatAreOmittedFromJson() throws Exception {
        AgentServiceImpl service = service();
        AgentDO stored = agent(7, "alice", false);
        stored.setAdmin("[\"carol\"]");
        stored.setViewer("[\"dave\"]");
        stored.setAdminOrg("[\"org-admin\"]");
        stored.setViewOrg("[\"org-view\"]");
        stored.setIsOpen(1);
        doReturn(stored).when(service).getById(7);
        doReturn(true).when(service).updateById(any(AgentDO.class));
        Agent partialUpdate = new ObjectMapper().readValue(
                "{\"id\":7,\"name\":\"updated\",\"toolConfig\":\"{}\"}", Agent.class);

        service.updateAgent(partialUpdate, User.get(2L, "alice"));

        ArgumentCaptor<AgentDO> saved = ArgumentCaptor.forClass(AgentDO.class);
        verify(service).updateById(saved.capture());
        assertEquals(stored.getAdmin(), saved.getValue().getAdmin());
        assertEquals(stored.getViewer(), saved.getValue().getViewer());
        assertEquals(stored.getAdminOrg(), saved.getValue().getAdminOrg());
        assertEquals(stored.getViewOrg(), saved.getValue().getViewOrg());
        assertEquals(stored.getIsOpen(), saved.getValue().getIsOpen());
    }

    @Test
    void ownerUpdateCanExplicitlyClearPermissionFields() throws Exception {
        AgentServiceImpl service = service();
        AgentDO stored = agent(7, "alice", true);
        stored.setAdmin("[\"carol\"]");
        doReturn(stored).when(service).getById(7);
        doReturn(true).when(service).updateById(any(AgentDO.class));
        Agent explicitUpdate = new ObjectMapper().readValue(
                "{\"id\":7,\"name\":\"updated\",\"toolConfig\":\"{}\","
                        + "\"admins\":[],\"isOpen\":0}",
                Agent.class);

        service.updateAgent(explicitUpdate, User.get(2L, "alice"));

        ArgumentCaptor<AgentDO> saved = ArgumentCaptor.forClass(AgentDO.class);
        verify(service).updateById(saved.capture());
        assertEquals("[]", saved.getValue().getAdmin());
        assertEquals(0, saved.getValue().getIsOpen());
    }

    private AgentServiceImpl service() {
        AgentServiceImpl service = spy(new AgentServiceImpl());
        UserService userService = mock(UserService.class);
        when(userService.getUserAllOrgId(anyString())).thenReturn(Set.of());
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "memoryService", mock(MemoryService.class));
        ReflectionTestUtils.setField(service, "chatQueryService", mock(ChatQueryService.class));
        ReflectionTestUtils.setField(service, "chatModelService", mock(ChatModelService.class));
        ReflectionTestUtils.setField(service, "executor", mock(ThreadPoolExecutor.class));
        return service;
    }

    private Agent update(int id, String claimedCreator) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("updated");
        agent.setCreatedBy(claimedCreator);
        agent.setToolConfig("{}");
        return agent;
    }

    private AgentDO agent(int id, String creator, boolean open) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setName("agent-" + id);
        agent.setCreatedBy(creator);
        agent.setCreatedAt(new Date());
        agent.setExamples("[]");
        agent.setToolConfig("{}");
        agent.setChatModelConfig("{}");
        agent.setVisualConfig("{}");
        agent.setAdmin("[]");
        agent.setViewer("[]");
        agent.setAdminOrg("[]");
        agent.setViewOrg("[]");
        agent.setIsOpen(open ? 1 : 0);
        return agent;
    }
}
