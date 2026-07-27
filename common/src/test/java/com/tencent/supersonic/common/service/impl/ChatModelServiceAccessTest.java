package com.tencent.supersonic.common.service.impl;

import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.persistence.dataobject.ChatModelDO;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ChatModelServiceAccessTest {

    @Test
    void nonAdministratorCannotMutateChatModels() {
        ChatModelServiceImpl service = spy(new ChatModelServiceImpl());
        User user = User.get(2L, "alice");
        ChatModel model = model();

        assertThrows(InvalidPermissionException.class, () -> service.createChatModel(model, user));
        assertThrows(InvalidPermissionException.class, () -> service.updateChatModel(model, user));
        assertThrows(InvalidPermissionException.class,
                () -> service.deleteChatModel(model.getId(), user));
    }

    @Test
    void viewerReceivesModelMetadataWithoutCredentials() {
        ChatModelServiceImpl service = spy(new ChatModelServiceImpl());
        doReturn(List.of(modelData())).when(service).list();

        ChatModel visible = service.getChatModels(User.get(2L, "alice")).get(0);

        assertEquals("OPEN_AI", visible.getConfig().getProvider());
        assertEquals("bank-model", visible.getConfig().getModelName());
        assertNull(visible.getConfig().getApiKey());
        assertNull(visible.getConfig().getSecretKey());
    }

    @Test
    void superAdministratorReceivesCredentialsAndPreservesCreationMetadataOnUpdate() {
        ChatModelServiceImpl service = spy(new ChatModelServiceImpl());
        ChatModel existing = model();
        existing.setCreatedBy("original-owner");
        existing.setCreatedAt(new Date(1_000L));
        doReturn(existing).when(service).getChatModel(existing.getId());
        doReturn(true).when(service).updateById(any(ChatModelDO.class));
        ChatModel update = model();
        update.setCreatedBy("spoofed-owner");
        update.setCreatedAt(new Date(2_000L));

        ChatModel result = service.updateChatModel(update, User.getDefaultUser());

        ArgumentCaptor<ChatModelDO> persisted = ArgumentCaptor.forClass(ChatModelDO.class);
        verify(service).updateById(persisted.capture());
        assertEquals("original-owner", persisted.getValue().getCreatedBy());
        assertEquals(new Date(1_000L), persisted.getValue().getCreatedAt());
        assertEquals("encrypted-api-key", result.getConfig().getApiKey());
        assertEquals("encrypted-secret", result.getConfig().getSecretKey());
    }

    private ChatModel model() {
        ChatModel model = new ChatModel();
        model.setId(7);
        model.setName("bank-model");
        model.setCreatedBy("owner");
        model.setAdmin("admin");
        model.setViewers(List.of("alice"));
        model.setConfig(ChatModelConfig.builder().provider("OPEN_AI").modelName("bank-model")
                .apiKey("encrypted-api-key").secretKey("encrypted-secret").build());
        return model;
    }

    private ChatModelDO modelData() {
        ChatModel model = model();
        ChatModelDO data = new ChatModelDO();
        data.setId(model.getId());
        data.setName(model.getName());
        data.setCreatedBy(model.getCreatedBy());
        data.setAdmin(model.getAdmin());
        data.setViewer(JsonUtil.toString(model.getViewers()));
        data.setConfig(JsonUtil.toString(model.getConfig()));
        data.setIsOpen(0);
        return data;
    }
}
