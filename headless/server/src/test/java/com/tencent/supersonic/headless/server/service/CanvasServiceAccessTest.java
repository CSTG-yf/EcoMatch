package com.tencent.supersonic.headless.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.CanvasReq;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.CanvasDO;
import com.tencent.supersonic.headless.server.persistence.mapper.CanvasDOMapper;
import com.tencent.supersonic.headless.server.service.impl.CanvasServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasServiceAccessTest {

    private final CanvasDOMapper mapper = mock(CanvasDOMapper.class);
    private final DomainService domainService = mock(DomainService.class);
    private final CanvasServiceImpl service = new CanvasServiceImpl();
    private final User administrator = User.get(2L, "domain-admin");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "domainService", domainService);
    }

    @Test
    void anonymousAndViewerCannotReadLegacyCanvas() {
        assertThrows(InvalidPermissionException.class, () -> service.getCanvasList(10L, null));
        when(domainService.getDomainAuthSet(administrator, AuthType.ADMIN)).thenReturn(Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.getCanvasList(10L, administrator));

        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void domainAdministratorCanReadLegacyCanvas() {
        allowAdmin(10L);

        service.getCanvasList(10L, administrator);

        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    void updateCannotMoveLegacyCanvasAcrossDomainsOrOverwriteCreator() {
        allowAdmin(10L);
        CanvasDO existing = new CanvasDO();
        existing.setId(1L);
        existing.setDomainId(10L);
        existing.setCreatedBy("creator");
        when(mapper.selectById(1L)).thenReturn(existing);
        CanvasReq request = new CanvasReq();
        request.setId(1L);
        request.setDomainId(11L);
        request.setCreatedBy("attacker");

        assertThrows(InvalidArgumentException.class,
                () -> service.createOrUpdateCanvas(request, administrator));

        assertEquals("creator", existing.getCreatedBy());
        verify(mapper, never()).updateById(any(CanvasDO.class));
    }

    private void allowAdmin(Long domainId) {
        DomainResp domain = new DomainResp();
        domain.setId(domainId);
        when(domainService.getDomainAuthSet(administrator, AuthType.ADMIN))
                .thenReturn(Set.of(domain));
    }
}
