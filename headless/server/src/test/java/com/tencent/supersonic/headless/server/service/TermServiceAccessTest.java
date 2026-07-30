package com.tencent.supersonic.headless.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.TermDO;
import com.tencent.supersonic.headless.server.persistence.mapper.TermMapper;
import com.tencent.supersonic.headless.server.service.impl.TermServiceImpl;
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

class TermServiceAccessTest {

    private final DomainService domainService = mock(DomainService.class);
    private final TermMapper mapper = mock(TermMapper.class);
    private final TermServiceImpl service = new TermServiceImpl(domainService);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void anonymousOrUnauthorizedUserCannotReadTerms() {
        assertThrows(InvalidPermissionException.class, () -> service.getTerms(10L, null, null));
        assertThrows(InvalidPermissionException.class,
                () -> service.getTerms(10L, null, User.getVisitUser()));
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of());
        assertThrows(InvalidPermissionException.class,
                () -> service.getTerms(10L, null, User.get(2L, "analyst")));

        verifyNoInteractions(mapper);
    }

    @Test
    void domainViewerCanReadTerms() {
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of(domain(10L)));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(term(1L, 10L)));

        List<TermResp> terms = service.getTerms(10L, null, User.get(2L, "viewer"));

        assertEquals(List.of(1L), terms.stream().map(TermResp::getId).toList());
    }

    @Test
    void domainViewerCannotDeleteTerm() {
        when(mapper.selectById(1L)).thenReturn(term(1L, 10L));
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class))).thenAnswer(
                invocation -> invocation.getArgument(1) == AuthType.VIEWER ? Set.of(domain(10L))
                        : Set.of());

        assertThrows(InvalidPermissionException.class,
                () -> service.delete(1L, User.get(2L, "viewer")));

        verify(mapper).selectById(1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingTermFailsClosedBeforeDelete() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThrows(InvalidArgumentException.class,
                () -> service.delete(99L, User.getDefaultUser()));

        verify(mapper).selectById(99L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void updatePreservesServerOwnedCreationMetadata() {
        Date createdAt = new Date(1_000);
        TermDO existing = term(1L, 10L);
        existing.setCreatedBy("creator");
        existing.setCreatedAt(createdAt);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(domainService.getDomainAuthSet(any(User.class), any(AuthType.class)))
                .thenReturn(Set.of(domain(10L)));
        TermReq request = new TermReq();
        request.setId(1L);
        request.setDomainId(10L);
        request.setName("updated");
        request.setCreatedBy("attacker");
        request.setCreatedAt(new Date(2_000));

        service.saveOrUpdate(request, User.get(2L, "analyst"));

        ArgumentCaptor<TermDO> captor = ArgumentCaptor.forClass(TermDO.class);
        verify(mapper).insertOrUpdate(captor.capture());
        assertEquals("creator", captor.getValue().getCreatedBy());
        assertEquals(createdAt, captor.getValue().getCreatedAt());
        assertEquals("analyst", captor.getValue().getUpdatedBy());
    }

    private TermDO term(Long id, Long domainId) {
        TermDO term = new TermDO();
        term.setId(id);
        term.setDomainId(domainId);
        term.setName("term_" + id);
        return term;
    }

    private DomainResp domain(Long id) {
        DomainResp domain = new DomainResp();
        domain.setId(id);
        return domain;
    }
}
