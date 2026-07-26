package com.tencent.supersonic.chat.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.request.ChatMemoryDeleteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatMemoryUpdateReq;
import com.tencent.supersonic.chat.api.pojo.request.PageMemoryReq;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatMemoryDO;
import com.tencent.supersonic.chat.server.persistence.mapper.ChatMemoryMapper;
import com.tencent.supersonic.chat.server.persistence.repository.ChatMemoryRepository;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.ExemplarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceAccessTest {

    private final ChatMemoryRepository repository = mock(ChatMemoryRepository.class);
    private final ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
    private final AgentService agentService = mock(AgentService.class);
    private final MemoryServiceImpl service = new MemoryServiceImpl();
    private final User alice = User.get(2L, "alice");

    @BeforeEach
    void setUp() {
        Agent manageable = new Agent();
        manageable.setId(1);
        when(agentService.getAgents(any(User.class), eq(AuthType.ADMIN)))
                .thenReturn(List.of(manageable));
        ReflectionTestUtils.setField(service, "chatMemoryRepository", repository);
        ReflectionTestUtils.setField(service, "chatMemoryMapper", mapper);
        ReflectionTestUtils.setField(service, "agentService", agentService);
        ReflectionTestUtils.setField(service, "exemplarService", mock(ExemplarService.class));
        ReflectionTestUtils.setField(service, "embeddingConfig", mock(EmbeddingConfig.class));
    }

    @Test
    void rejectsCreateAndUpdateForUnmanageableAgent() {
        ChatMemory memory = ChatMemory.builder().agentId(2).question("secret")
                .status(MemoryStatus.PENDING).build();
        ChatMemoryDO stored = memory(9L, 2);
        when(repository.getMemory(9L)).thenReturn(stored);

        assertThrows(InvalidPermissionException.class, () -> service.createMemory(memory, alice));
        assertThrows(InvalidPermissionException.class,
                () -> service.updateMemory(ChatMemoryUpdateReq.builder().id(9L).build(), alice));

        verify(repository, never()).createMemory(any());
        verify(mapper, never()).update(any());
    }

    @Test
    void mixedAgentBatchDeleteFailsBeforeAnySideEffect() {
        when(repository.getMemories(any(QueryWrapper.class)))
                .thenReturn(List.of(memory(1L, 1), memory(2L, 2)));
        ChatMemoryDeleteReq request = ChatMemoryDeleteReq.builder().ids(List.of(1L, 2L)).build();

        assertThrows(InvalidPermissionException.class, () -> service.batchDelete(request, alice));

        verify(repository, never()).batchDelete(any());
    }

    @Test
    void rejectsUnboundedDeleteAndScopesUnfilteredPageToManageableAgents() {
        assertThrows(InvalidArgumentException.class,
                () -> service.batchDelete(new ChatMemoryDeleteReq(), alice));
        when(repository.getMemories(any(QueryWrapper.class))).thenReturn(List.of());

        service.pageMemories(new PageMemoryReq(), alice);

        ArgumentCaptor<QueryWrapper<ChatMemoryDO>> query =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(repository).getMemories(query.capture());
        assertFalse(query.getValue().getExpression().getNormal().isEmpty());
    }

    private ChatMemoryDO memory(long id, int agentId) {
        return ChatMemoryDO.builder().id(id).agentId(agentId)
                .status(MemoryStatus.PENDING.toString()).build();
    }
}
