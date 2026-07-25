package com.tencent.supersonic.chat.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tencent.supersonic.chat.api.pojo.request.PluginQueryReq;
import com.tencent.supersonic.chat.server.persistence.dataobject.PluginDO;
import com.tencent.supersonic.chat.server.persistence.repository.PluginRepository;
import com.tencent.supersonic.chat.server.plugin.ChatPlugin;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginServiceAccessTest {

    @Test
    void rejectsCrossUserMutationAndPreservesCreatorOnOwnerUpdate() {
        PluginRepository repository = mock(PluginRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PluginServiceImpl service = new PluginServiceImpl(repository, publisher);
        PluginDO stored = plugin(11L, "alice");
        when(repository.getPlugin(11L)).thenReturn(stored);
        ChatPlugin update = new ChatPlugin();
        update.setId(11L);
        update.setName("updated");
        update.setCreatedBy("mallory");
        update.setDataSetList(List.of(1L));

        assertThrows(InvalidPermissionException.class,
                () -> service.updatePlugin(update, User.get(3L, "bob")));
        verify(repository, never()).updatePlugin(any());

        service.updatePlugin(update, User.get(2L, "alice"));

        ArgumentCaptor<PluginDO> saved = ArgumentCaptor.forClass(PluginDO.class);
        verify(repository).updatePlugin(saved.capture());
        assertEquals("alice", saved.getValue().getCreatedBy());
        assertEquals(11L, saved.getValue().getId());

        assertThrows(InvalidPermissionException.class,
                () -> service.deletePlugin(11L, User.get(3L, "bob")));
        verify(repository, never()).deletePlugin(11L);
    }

    @Test
    void managementQueryOnlyReturnsOwnedPluginsForNonAdmin() {
        PluginRepository repository = mock(PluginRepository.class);
        PluginServiceImpl service =
                new PluginServiceImpl(repository, mock(ApplicationEventPublisher.class));
        when(repository.query(any(QueryWrapper.class)))
                .thenReturn(List.of(plugin(1L, "alice"), plugin(2L, "bob")));

        List<ChatPlugin> visible =
                service.queryWithAuthCheck(new PluginQueryReq(), User.get(2L, "alice"));

        assertEquals(List.of(1L), visible.stream().map(ChatPlugin::getId).toList());
    }

    private PluginDO plugin(Long id, String owner) {
        PluginDO plugin = new PluginDO();
        plugin.setId(id);
        plugin.setName("plugin-" + id);
        plugin.setCreatedBy(owner);
        plugin.setCreatedAt(new Date());
        return plugin;
    }
}
