package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.service.ModelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelControllerAccessTest {

    private final ModelService modelService = mock(ModelService.class);
    private final ModelController controller = new ModelController(modelService);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final User user = User.get(2L, "alice");

    @Test
    void rejectsModelAndDatabaseReadsOutsideAdministratorScope() {
        when(modelService.getModelListWithAuth(user, null, AuthType.ADMIN))
                .thenReturn(List.of(model(1L)));

        withUser(() -> {
            assertThrows(InvalidPermissionException.class,
                    () -> controller.getModel(2L, request, response));
            assertThrows(InvalidPermissionException.class,
                    () -> controller.getModelDatabase(2L, request, response));
            return null;
        });

        verify(modelService, never()).getModel(2L);
        verify(modelService, never()).getDatabaseByModelId(2L);
    }

    @Test
    void allowsDetailedReadInsideAdministratorScope() {
        ModelResp detail = model(1L);
        when(modelService.getModelListWithAuth(user, null, AuthType.ADMIN))
                .thenReturn(List.of(detail));
        when(modelService.getModel(1L)).thenReturn(detail);

        ModelResp result = withUser(() -> controller.getModel(1L, request, response));

        assertEquals(1L, result.getId());
    }

    @Test
    void filtersBatchReadsToAccessibleModelIds() {
        when(modelService.getModelListWithAuth(user, null, AuthType.VIEWER))
                .thenReturn(List.of(model(1L)));
        when(modelService.getModelList(any(ModelFilter.class))).thenReturn(List.of(model(1L)));

        List<ModelResp> result =
                withUser(() -> controller.getModelListByIds("1", request, response));

        ArgumentCaptor<ModelFilter> filter = ArgumentCaptor.forClass(ModelFilter.class);
        verify(modelService).getModelList(filter.capture());
        assertEquals(List.of(1L), filter.getValue().getIds());
        assertEquals(List.of(1L), result.stream().map(ModelResp::getId).toList());
        withUser(() -> assertThrows(InvalidPermissionException.class,
                () -> controller.getModelListByIds("1,2", request, response)));
    }

    @Test
    void modelDatabaseReadNeverReturnsStoredPassword() {
        when(modelService.getModelListWithAuth(user, null, AuthType.ADMIN))
                .thenReturn(List.of(model(1L)));
        DatabaseResp database = new DatabaseResp();
        database.setPassword("encrypted-password");
        when(modelService.getDatabaseByModelId(1L)).thenReturn(database);

        DatabaseResp result = withUser(() -> controller.getModelDatabase(1L, request, response));

        assertNull(result.getPassword());
    }

    @Test
    void modelSchemaBuildRequiresSuperAdministrator() throws Exception {
        withUser(() -> assertThrows(InvalidPermissionException.class,
                () -> controller.buildModelSchema(new ModelBuildReq(), request, response)));

        verify(modelService, never()).buildModelSchema(any());
    }

    private ModelResp model(Long id) {
        ModelResp model = new ModelResp();
        model.setId(id);
        return model;
    }

    private <T> T withUser(ThrowingSupplier<T> action) {
        try (MockedStatic<UserHolder> userHolder = mockStatic(UserHolder.class)) {
            userHolder.when(() -> UserHolder.findUser(request, response)).thenReturn(user);
            return action.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
