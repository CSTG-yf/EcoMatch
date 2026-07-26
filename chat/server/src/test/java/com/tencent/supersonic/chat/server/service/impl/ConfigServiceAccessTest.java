package com.tencent.supersonic.chat.server.service.impl;

import com.tencent.supersonic.chat.api.pojo.request.ChatConfigBaseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatConfigEditReqReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatConfigFilter;
import com.tencent.supersonic.chat.api.pojo.response.ChatConfigResp;
import com.tencent.supersonic.chat.server.persistence.repository.ChatConfigRepository;
import com.tencent.supersonic.chat.server.util.ChatConfigHelper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigServiceAccessTest {

    private final ChatConfigRepository repository = mock(ChatConfigRepository.class);
    private final ChatConfigHelper helper = mock(ChatConfigHelper.class);
    private final SemanticLayerService semanticLayerService = mock(SemanticLayerService.class);
    private final ModelService modelService = mock(ModelService.class);
    private final ConfigServiceImpl service =
            new ConfigServiceImpl(repository, helper, semanticLayerService, modelService);
    private final User alice = User.get(2L, "alice");

    @BeforeEach
    void setUp() {
        when(modelService.getModelListWithAuth(any(User.class), eq(null), eq(AuthType.ADMIN)))
                .thenReturn(List.of(model(1L)));
        when(modelService.getModelListWithAuth(any(User.class), eq(null), eq(AuthType.VIEWER)))
                .thenReturn(List.of(model(1L)));
    }

    @Test
    void rejectsConfigMutationOutsideManagedModels() {
        ChatConfigBaseReq create = new ChatConfigBaseReq();
        create.setModelId(2L);
        ChatConfigEditReqReq edit = new ChatConfigEditReqReq();
        edit.setId(9L);
        ChatConfigResp stored = config(9L, 2L);
        when(repository.getChatConfig(any(ChatConfigFilter.class))).thenReturn(List.of(stored));

        assertThrows(InvalidPermissionException.class, () -> service.addConfig(create, alice));
        assertThrows(InvalidPermissionException.class, () -> service.editConfig(edit, alice));

        verify(repository, never()).createConfig(any());
        verify(repository, never()).updateConfig(any());
    }

    @Test
    void managementSearchOnlyReturnsAuthorizedModels() {
        when(repository.getChatConfig(any(ChatConfigFilter.class)))
                .thenReturn(List.of(config(1L, 1L), config(2L, 2L)));

        List<ChatConfigResp> visible = service.search(new ChatConfigFilter(), alice);

        assertEquals(List.of(1L), visible.stream().map(ChatConfigResp::getModelId).toList());
    }

    @Test
    void schemaReadRequiresViewerPermission() {
        DataSetSchema schema = new DataSetSchema();
        when(semanticLayerService.getDataSetSchema(1L)).thenReturn(schema);

        assertSame(schema, service.getDataSetSchema(1L, alice));
        assertThrows(InvalidPermissionException.class, () -> service.getDataSetSchema(2L, alice));
        verify(semanticLayerService, never()).getDataSetSchema(2L);
    }

    private ModelResp model(Long id) {
        ModelResp model = new ModelResp();
        model.setId(id);
        return model;
    }

    private ChatConfigResp config(Long id, Long modelId) {
        ChatConfigResp config = new ChatConfigResp();
        config.setId(id);
        config.setModelId(modelId);
        return config;
    }
}
