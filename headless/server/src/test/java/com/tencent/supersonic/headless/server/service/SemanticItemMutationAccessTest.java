package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.request.DimensionReq;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.persistence.repository.DimensionRepository;
import com.tencent.supersonic.headless.server.persistence.repository.MetricRepository;
import com.tencent.supersonic.headless.server.pojo.DimensionFilter;
import com.tencent.supersonic.headless.server.pojo.MetricFilter;
import com.tencent.supersonic.headless.server.service.impl.DimensionServiceImpl;
import com.tencent.supersonic.headless.server.service.impl.MetricServiceImpl;
import com.tencent.supersonic.headless.server.utils.AliasGenerateHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticItemMutationAccessTest {

    @Test
    void dimensionCreateChecksModelAdminBeforeRepositoryAccess() {
        DimensionRepository repository = mock(DimensionRepository.class);
        ModelService modelService = denyingModelService();
        DimensionServiceImpl service =
                new DimensionServiceImpl(repository, modelService, mock(AliasGenerateHelper.class),
                        mock(DatabaseService.class), mock(ModelRelaService.class),
                        mock(DataSetService.class), mock(ApplicationEventPublisher.class));
        DimensionReq request = new DimensionReq();
        request.setModelId(7L);

        assertThrows(InvalidPermissionException.class,
                () -> service.createDimension(request, User.get(2L, "analyst")));

        verifyNoInteractions(repository);
    }

    @Test
    void metricCreateChecksModelAdminBeforeRepositoryAccess() {
        MetricRepository repository = mock(MetricRepository.class);
        ModelService modelService = denyingModelService();
        MetricServiceImpl service = new MetricServiceImpl(repository, modelService,
                mock(AliasGenerateHelper.class), mock(CollectService.class),
                mock(DataSetService.class), mock(ApplicationEventPublisher.class),
                mock(DimensionService.class), mock(ChatLayerService.class));
        MetricReq request = new MetricReq();
        request.setModelId(7L);

        assertThrows(InvalidPermissionException.class,
                () -> service.createMetric(request, User.get(2L, "analyst")));

        verifyNoInteractions(repository);
    }

    @Test
    void dimensionReadIntersectsRequestedAndAccessibleModels() {
        DimensionRepository repository = mock(DimensionRepository.class);
        ModelService modelService = mock(ModelService.class);
        User user = User.get(2L, "viewer");
        when(modelService.getAccessibleModelIds(user, AuthType.VIEWER)).thenReturn(Set.of(1L));
        DimensionServiceImpl service =
                new DimensionServiceImpl(repository, modelService, mock(AliasGenerateHelper.class),
                        mock(DatabaseService.class), mock(ModelRelaService.class),
                        mock(DataSetService.class), mock(ApplicationEventPublisher.class));
        MetaFilter filter = new MetaFilter(List.of(1L, 2L));

        service.getDimensions(filter, user);

        ArgumentCaptor<DimensionFilter> captor = ArgumentCaptor.forClass(DimensionFilter.class);
        verify(repository).getDimension(captor.capture());
        assertEquals(List.of(1L), captor.getValue().getModelIds());
    }

    @Test
    void metricReadIntersectsRequestedAndAccessibleModels() {
        MetricRepository repository = mock(MetricRepository.class);
        ModelService modelService = mock(ModelService.class);
        User user = User.get(2L, "viewer");
        when(modelService.getAccessibleModelIds(user, AuthType.VIEWER)).thenReturn(Set.of(1L));
        MetricServiceImpl service = new MetricServiceImpl(repository, modelService,
                mock(AliasGenerateHelper.class), mock(CollectService.class),
                mock(DataSetService.class), mock(ApplicationEventPublisher.class),
                mock(DimensionService.class), mock(ChatLayerService.class));
        MetaFilter filter = new MetaFilter(List.of(1L, 2L));

        service.getMetrics(filter, user);

        ArgumentCaptor<MetricFilter> captor = ArgumentCaptor.forClass(MetricFilter.class);
        verify(repository).getMetric(captor.capture());
        assertEquals(List.of(1L), captor.getValue().getModelIds());
    }

    private ModelService denyingModelService() {
        ModelService modelService = mock(ModelService.class);
        doThrow(new InvalidPermissionException("denied")).when(modelService).requireModelAdmin(7L,
                User.get(2L, "analyst"));
        return modelService;
    }
}
