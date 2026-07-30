package com.tencent.supersonic.headless.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardAccessScope;
import com.tencent.supersonic.headless.api.pojo.enums.DashboardStatus;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCopyReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardCreateReq;
import com.tencent.supersonic.headless.api.pojo.request.DashboardUpdateReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DashboardDO;
import com.tencent.supersonic.headless.server.persistence.mapper.DashboardMapper;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.service.impl.DashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private final DashboardMapper mapper = mock(DashboardMapper.class);
    private final DomainService domainService = mock(DomainService.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final DashboardServiceImpl service =
            new DashboardServiceImpl(mapper, domainService, auditPublisher, new ObjectMapper());
    private final User owner = User.get(2L, "owner");

    @BeforeEach
    void setUp() {
        allow(owner, AuthType.VIEWER, 10L);
    }

    @Test
    void anonymousIdentityCannotListOrCreate() {
        DashboardCreateReq request = createRequest();

        assertThrows(InvalidPermissionException.class, () -> service.list(10L, null, 1, 20, null));
        assertThrows(InvalidPermissionException.class, () -> service.create(request, null));

        verifyNoInteractions(mapper);
    }

    @Test
    void createsDraftWithServerOwnedMetadata() {
        DashboardCreateReq request = createRequest();
        when(mapper.insert(any(DashboardDO.class))).thenAnswer(invocation -> {
            invocation.<DashboardDO>getArgument(0).setId(7L);
            return 1;
        });

        DashboardResp response = service.create(request, owner);

        ArgumentCaptor<DashboardDO> captor = ArgumentCaptor.forClass(DashboardDO.class);
        verify(mapper).insert(captor.capture());
        DashboardDO saved = captor.getValue();
        assertEquals("owner", saved.getOwner());
        assertEquals("owner", saved.getCreatedBy());
        assertEquals(DashboardStatus.DRAFT.name(), saved.getStatus());
        assertEquals(0, saved.getVersion());
        assertNotNull(saved.getCreatedAt());
        assertEquals(7L, response.getId());
    }

    @Test
    void rejectsSensitiveOrOversizedConfig() {
        DashboardCreateReq sensitive = createRequest();
        sensitive.setConfig("{\"components\":[],\"access_token\":\"secret\"}");
        DashboardCreateReq tooMany = createRequest();
        tooMany.setConfig("{\"components\":[" + "{},".repeat(100) + "{}]}");

        assertThrows(InvalidArgumentException.class, () -> service.create(sensitive, owner));
        assertThrows(InvalidArgumentException.class, () -> service.create(tooMany, owner));

        verify(mapper, never()).insert(any(DashboardDO.class));
    }

    @Test
    void privateDashboardCannotBeReadByAnotherViewer() {
        User viewer = User.get(3L, "viewer");
        allow(viewer, AuthType.VIEWER, 10L);
        when(mapper.selectById(1L)).thenReturn(
                dashboard(1L, DashboardStatus.DRAFT, DashboardAccessScope.PRIVATE, "owner"));

        assertThrows(InvalidPermissionException.class, () -> service.get(1L, viewer));
    }

    @Test
    void publishedOrganizationDashboardRequiresMatchingTrustedAttribute() {
        DashboardDO dashboard = dashboard(1L, DashboardStatus.PUBLISHED,
                DashboardAccessScope.ORGANIZATION, "owner");
        dashboard.setOrganizationId("ORG-1");
        when(mapper.selectById(1L)).thenReturn(dashboard);
        User matching = userWithOrganization("matching", "ORG-1");
        User other = userWithOrganization("other", "ORG-2");
        allow(matching, AuthType.VIEWER, 10L);
        allow(other, AuthType.VIEWER, 10L);

        assertEquals(1L, service.get(1L, matching).getId());
        assertThrows(InvalidPermissionException.class, () -> service.get(1L, other));
    }

    @Test
    void updateRequiresOwnerOrDomainAdministratorAndCurrentVersion() {
        DashboardDO dashboard =
                dashboard(1L, DashboardStatus.DRAFT, DashboardAccessScope.PRIVATE, "owner");
        dashboard.setCreatedBy("creator");
        when(mapper.selectById(1L)).thenReturn(dashboard);
        when(mapper.updateWithVersion(any())).thenReturn(1);
        DashboardUpdateReq request = updateRequest(0);

        DashboardResp response = service.update(1L, request, owner);

        assertEquals(1, response.getVersion());
        assertEquals("creator", response.getCreatedBy());
        assertEquals("owner", response.getUpdatedBy());
        assertThrows(InvalidArgumentException.class,
                () -> service.update(1L, updateRequest(9), owner));
    }

    @Test
    void databaseRaceReturnsVersionConflict() {
        DashboardDO dashboard =
                dashboard(1L, DashboardStatus.DRAFT, DashboardAccessScope.PRIVATE, "owner");
        when(mapper.selectById(1L)).thenReturn(dashboard);
        when(mapper.updateWithVersion(any())).thenReturn(0);

        InvalidArgumentException failure = assertThrows(InvalidArgumentException.class,
                () -> service.update(1L, updateRequest(0), owner));

        assertTrue(failure.getMessage().contains("version conflict"));
    }

    @Test
    void publishThenDisableUsesExplicitStateTransitions() {
        DashboardDO dashboard =
                dashboard(1L, DashboardStatus.DRAFT, DashboardAccessScope.DOMAIN, "owner");
        when(mapper.selectById(1L)).thenReturn(dashboard);
        when(mapper.updateWithVersion(any())).thenReturn(1);

        DashboardResp published = service.publish(1L, 0, owner);
        DashboardResp disabled = service.disable(1L, 1, owner);

        assertEquals(DashboardStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishedAt());
        assertEquals(DashboardStatus.DISABLED, disabled.getStatus());
        assertNotNull(disabled.getDisabledAt());
    }

    @Test
    void copyCreatesPrivateDraftAndAuditsSourceIdentity() {
        DashboardDO source =
                dashboard(1L, DashboardStatus.PUBLISHED, DashboardAccessScope.DOMAIN, "owner");
        when(mapper.selectById(1L)).thenReturn(source);
        when(mapper.insert(any(DashboardDO.class))).thenAnswer(invocation -> {
            invocation.<DashboardDO>getArgument(0).setId(2L);
            return 1;
        });
        DashboardCopyReq request = new DashboardCopyReq();
        request.setName("Copied dashboard");

        DashboardResp copied = service.copy(1L, request, owner);

        assertEquals(2L, copied.getId());
        assertEquals(DashboardStatus.DRAFT, copied.getStatus());
        assertEquals(DashboardAccessScope.PRIVATE, copied.getAccessScope());
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, org.mockito.Mockito.atLeastOnce())
                .publishBestEffort(events.capture(), eq(owner));
        assertTrue(events.getAllValues().stream()
                .anyMatch(event -> event.getEventType() == AuditEventType.DASHBOARD_COPIED
                        && Long.valueOf(1L).equals(event.getMetadata().get("sourceId"))));
    }

    @Test
    void domainAdministratorCanDeleteAnotherUsersDashboard() {
        User administrator = User.get(4L, "domain-admin");
        allow(administrator, AuthType.VIEWER, 10L);
        allow(administrator, AuthType.ADMIN, 10L);
        when(mapper.selectById(1L)).thenReturn(
                dashboard(1L, DashboardStatus.DRAFT, DashboardAccessScope.PRIVATE, "owner"));
        when(mapper.deleteById(1L)).thenReturn(1);

        service.delete(1L, administrator);

        verify(mapper).deleteById(1L);
    }

    @Test
    void regularViewerCannotModifyAnotherUsersDashboard() {
        User viewer = User.get(3L, "viewer");
        allow(viewer, AuthType.VIEWER, 10L);
        when(domainService.getDomainAuthSet(viewer, AuthType.ADMIN)).thenReturn(Set.of());
        when(mapper.selectById(1L)).thenReturn(
                dashboard(1L, DashboardStatus.PUBLISHED, DashboardAccessScope.DOMAIN, "owner"));

        assertThrows(InvalidPermissionException.class,
                () -> service.update(1L, updateRequest(0), viewer));

        verify(mapper, never()).updateWithVersion(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listAppliesVisibilityInsideDatabaseQuery() {
        when(domainService.getDomainAuthSet(owner, AuthType.ADMIN)).thenReturn(Set.of());
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                dashboard(1L, DashboardStatus.PUBLISHED, DashboardAccessScope.DOMAIN, "owner")));

        service.list(10L, DashboardStatus.PUBLISHED, 1, 20, owner);

        verify(mapper).selectList(any(Wrapper.class));
        assertThrows(InvalidArgumentException.class, () -> service.list(10L, null, 0, 20, owner));
        assertThrows(InvalidArgumentException.class, () -> service.list(10L, null, 1, 101, owner));
    }

    private DashboardCreateReq createRequest() {
        DashboardCreateReq request = new DashboardCreateReq();
        request.setDomainId(10L);
        request.setName("Operations dashboard");
        request.setConfig("{\"components\":[]}");
        return request;
    }

    private DashboardUpdateReq updateRequest(int version) {
        DashboardUpdateReq request = new DashboardUpdateReq();
        request.setVersion(version);
        request.setName("Updated dashboard");
        request.setAccessScope(DashboardAccessScope.PRIVATE);
        request.setConfig("{\"components\":[]}");
        return request;
    }

    private DashboardDO dashboard(Long id, DashboardStatus status, DashboardAccessScope accessScope,
            String ownerName) {
        DashboardDO dashboard = new DashboardDO();
        dashboard.setId(id);
        dashboard.setDomainId(10L);
        dashboard.setName("Dashboard");
        dashboard.setStatus(status.name());
        dashboard.setAccessScope(accessScope.name());
        dashboard.setOwner(ownerName);
        dashboard.setCreatedBy(ownerName);
        dashboard.setUpdatedBy(ownerName);
        dashboard.setConfig("{\"components\":[]}");
        dashboard.setVersion(0);
        return dashboard;
    }

    private User userWithOrganization(String name, String organizationId) {
        User user = User.get(3L, name);
        user.getAttributes().put("organizationId", organizationId);
        return user;
    }

    private void allow(User user, AuthType authType, Long domainId) {
        DomainResp domain = new DomainResp();
        domain.setId(domainId);
        when(domainService.getDomainAuthSet(eq(user), eq(authType))).thenReturn(Set.of(domain));
    }
}
