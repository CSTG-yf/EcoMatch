package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ModelDO;
import com.tencent.supersonic.headless.server.persistence.repository.DateInfoRepository;
import com.tencent.supersonic.headless.server.persistence.repository.ModelRepository;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.service.impl.ModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelServiceAccessBoundaryTest {

    private final ModelRepository modelRepository = mock(ModelRepository.class);
    private final DatabaseService databaseService = mock(DatabaseService.class);
    private final DimensionService dimensionService = mock(DimensionService.class);
    private final MetricService metricService = mock(MetricService.class);
    private final DomainService domainService = mock(DomainService.class);
    private final UserService userService = mock(UserService.class);
    private ModelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModelServiceImpl(modelRepository, databaseService, dimensionService,
                metricService, domainService, userService, mock(DataSetService.class),
                mock(DateInfoRepository.class), mock(ModelRelaService.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void rejectsModelCreationBeforeDatabaseOrPersistenceAccess() {
        User user = User.get(2L, "analyst");
        ModelReq request = new ModelReq();
        request.setDomainId(10L);
        request.setDatabaseId(20L);
        request.setModelDetail(new ModelDetail());
        when(domainService.getDomainAuthSet(user, AuthType.ADMIN)).thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class, () -> service.createModel(request, user));

        verifyNoInteractions(databaseService);
        verify(modelRepository, never()).createModel(any());
    }

    @Test
    void rejectsSchemaBuildBeforeDatabaseMetadataAccess() {
        User user = User.get(2L, "analyst");
        ModelBuildReq request = new ModelBuildReq();
        request.setDomainId(10L);
        request.setDatabaseId(20L);
        when(domainService.getDomainAuthSet(user, AuthType.ADMIN)).thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.buildModelSchema(request, user));

        verifyNoInteractions(databaseService);
    }

    @Test
    void allowsDomainAdministratorToCreateAgainstAccessibleDatabase() {
        User user = User.get(2L, "domain-admin");
        ModelReq request = new ModelReq();
        request.setDomainId(10L);
        request.setDatabaseId(20L);
        request.setModelDetail(new ModelDetail());
        DomainResp domain = new DomainResp();
        domain.setId(10L);
        when(domainService.getDomainAuthSet(user, AuthType.ADMIN)).thenReturn(Set.of(domain));
        when(databaseService.getDatabase(20L, user)).thenReturn(new DatabaseResp());
        doAnswer(invocation -> {
            invocation.<ModelDO>getArgument(0).setId(1L);
            return null;
        }).when(modelRepository).createModel(any());

        assertDoesNotThrow(() -> service.createModel(request, user));

        verify(modelRepository).createModel(any());
    }

    @Test
    void rejectsUnauthorizedUpdateBeforePersistence() {
        User user = User.get(2L, "analyst");
        ModelDO existing = model(1L, 10L, "owner", "model-admin", "viewer");
        when(modelRepository.getModelById(1L)).thenReturn(existing);
        when(userService.getUserAllOrgId(user.getName())).thenReturn(Set.of());
        when(domainService.getDomainAuthSet(user, AuthType.ADMIN)).thenReturn(Set.of());
        ModelReq request = new ModelReq();
        request.setId(1L);

        assertThrows(InvalidPermissionException.class, () -> service.updateModel(request, user));

        verify(modelRepository, never()).updateModel(any());
    }

    @Test
    void rejectsVisitUserBeforeLoadingModel() {
        assertThrows(InvalidPermissionException.class,
                () -> service.requireModelViewer(1L, User.getVisitUser()));

        verify(modelRepository, never()).getModelById(any());
    }

    @Test
    void allowsExplicitModelViewer() {
        User user = User.get(3L, "viewer");
        when(modelRepository.getModelById(1L))
                .thenReturn(model(1L, 10L, "owner", "model-admin", "viewer"));
        when(userService.getUserAllOrgId(user.getName())).thenReturn(Set.of());

        assertDoesNotThrow(() -> service.requireModelViewer(1L, user));
    }

    @Test
    void rejectsIncompleteBatchBeforeWritingAnyModel() {
        MetaBatchReq request = new MetaBatchReq();
        request.setIds(List.of(1L, 2L));
        when(modelRepository.getModelList(any(ModelFilter.class)))
                .thenReturn(List.of(model(1L, 10L, "owner", "admin", "viewer")));

        assertThrows(InvalidArgumentException.class,
                () -> service.batchUpdateStatus(request, User.getDefaultUser()));

        verify(modelRepository, never()).batchUpdate(any());
    }

    private ModelDO model(Long id, Long domainId, String creator, String admin, String viewer) {
        ModelDO model = new ModelDO();
        model.setId(id);
        model.setDomainId(domainId);
        model.setDatabaseId(20L);
        model.setCreatedBy(creator);
        model.setAdmin(admin);
        model.setViewer(viewer);
        model.setStatus(StatusEnum.ONLINE.getCode());
        return model;
    }
}
