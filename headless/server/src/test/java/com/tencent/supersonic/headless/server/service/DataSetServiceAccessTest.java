package com.tencent.supersonic.headless.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.DataSetReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DataSetDO;
import com.tencent.supersonic.headless.server.persistence.mapper.DataSetDOMapper;
import com.tencent.supersonic.headless.server.service.impl.DataSetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DataSetServiceAccessTest {

    private final DomainService domainService = mock(DomainService.class);
    private final DataSetDOMapper mapper = mock(DataSetDOMapper.class);
    private final DataSetServiceImpl service = new DataSetServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "domainService", domainService);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void unauthorizedUserCannotReadDataSetConfiguration() {
        when(mapper.selectById(1L)).thenReturn(dataSet(1L, 10L, "owner"));
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.getDataSet(1L, User.get(2L, "analyst")));

        verify(mapper).selectById(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void explicitDataSetAdminCanReadOnlyOwnConfiguration() {
        when(mapper.selectById(1L)).thenReturn(dataSet(1L, 10L, "analyst"));

        DataSetResp response = service.getDataSet(1L, User.get(2L, "analyst"));

        assertEquals(1L, response.getId());
        verifyNoInteractions(domainService);
    }

    @Test
    void visitIdentityCannotReuseHistoricalAdminMetadata() {
        when(mapper.selectById(1L)).thenReturn(dataSet(1L, 10L, "visit"));

        assertThrows(InvalidPermissionException.class,
                () -> service.getDataSet(1L, User.getVisitUser()));

        verifyNoInteractions(domainService);
    }

    @Test
    void domainViewerCanReadOnlyDataSetDomainId() {
        DataSetDO dataSet = dataSet(1L, 10L, "owner");
        dataSet.setDataSetDetail("not-json");
        User viewer = User.get(2L, "analyst");
        DomainResp domain = new DomainResp();
        domain.setId(10L);
        when(mapper.selectById(1L)).thenReturn(dataSet);
        when(domainService.getDomainAuthSet(viewer, AuthType.VIEWER)).thenReturn(Set.of(domain));

        assertEquals(10L, service.getDataSetDomainId(1L, viewer));

        verify(mapper).selectById(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void userWithoutDataSetOrDomainViewerPermissionCannotReadDomainId() {
        User analyst = User.get(2L, "analyst");
        when(mapper.selectById(1L)).thenReturn(dataSet(1L, 10L, "owner"));
        when(domainService.getDomainAuthSet(analyst, AuthType.VIEWER)).thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.getDataSetDomainId(1L, analyst));
    }

    @Test
    void explicitDataSetAdminCanReadDomainIdWithoutDomainPermission() {
        when(mapper.selectById(1L)).thenReturn(dataSet(1L, 10L, "analyst"));

        assertEquals(10L, service.getDataSetDomainId(1L, User.get(2L, "analyst")));

        verifyNoInteractions(domainService);
    }

    @Test
    void listFiltersOutDataSetsOutsideUserAdministrationScope() {
        when(mapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(dataSet(1L, 10L, "analyst"), dataSet(2L, 10L, "owner")));
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of());

        List<DataSetResp> responses = service.getDataSetList(10L,
                List.of(StatusEnum.ONLINE.getCode()), User.get(2L, "analyst"));

        assertEquals(List.of(1L), responses.stream().map(DataSetResp::getId).toList());
    }

    @Test
    void createRequiresTargetDomainAdministrationBeforePersistence() {
        DataSetReq request = new DataSetReq();
        request.setDomainId(10L);
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.save(request, User.get(2L, "analyst")));

        verifyNoInteractions(mapper);
    }

    @Test
    void updatePreservesServerOwnedCreationMetadata() {
        Date createdAt = new Date(1_000);
        DataSetDO existing = dataSet(1L, 10L, "analyst");
        existing.setCreatedBy("creator");
        existing.setCreatedAt(createdAt);
        when(mapper.selectById(1L)).thenReturn(existing);
        DataSetReq request = new DataSetReq();
        request.setId(1L);
        request.setDomainId(10L);
        request.setName("updated");
        request.setCreatedBy("attacker");
        request.setCreatedAt(new Date(2_000));

        service.update(request, User.get(2L, "analyst"));

        ArgumentCaptor<DataSetDO> captor = ArgumentCaptor.forClass(DataSetDO.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("creator", captor.getValue().getCreatedBy());
        assertEquals(createdAt, captor.getValue().getCreatedAt());
        assertEquals("analyst", captor.getValue().getUpdatedBy());
    }

    @Test
    void missingDataSetFailsClosed() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThrows(InvalidArgumentException.class,
                () -> service.getDataSet(99L, User.getDefaultUser()));
    }

    private DataSetDO dataSet(Long id, Long domainId, String admin) {
        DataSetDO dataSet = new DataSetDO();
        dataSet.setId(id);
        dataSet.setDomainId(domainId);
        dataSet.setName("data_set_" + id);
        dataSet.setBizName("data_set_" + id);
        dataSet.setStatus(StatusEnum.ONLINE.getCode());
        dataSet.setAdmin(admin);
        dataSet.setCreatedBy("creator");
        return dataSet;
    }

}
